package com.lawrence.supportagent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.awaitility.Awaitility.await;

import com.lawrence.supportagent.chat.ChatExecutionControl.CancellationReason;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 无网络验证有界发送通道关闭、真实写入确认和资源注册竞争。 */
class BoundedSseSinkTest {
    /** 正常结束不取消；即使队列已满也能保留正常关闭意图。 */
    @Test
    void shouldCompleteNormallyWithFullQueue() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        ChatExecutionControl control = new ChatExecutionControl();
        BoundedSseSink sink = new BoundedSseSink(emitter, control, 2, Duration.ofSeconds(5));
        sink.heartbeat();
        sink.heartbeat();
        sink.complete();
        sink.heartbeat();
        sink.drain();
        sink.onCompletion();
        sink.abort(CancellationReason.CHAT_RESPONSE_TIMEOUT);
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        assertThat(control.isCancelled()).isFalse();
        assertThatThrownBy(() -> sink.send(event())).isInstanceOf(IllegalStateException.class);
    }

    /** 空队列中的发送线程必须被唤醒，即使尚未注册任务句柄。 */
    @Test
    void shouldWakeWaitingDrainAndStopLateResources() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        ChatExecutionControl control = new ChatExecutionControl();
        BoundedSseSink sink = new BoundedSseSink(emitter, control);
        AtomicReference<Thread> waiter = new AtomicReference<>();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> drain = workers.submit(() -> { waiter.set(Thread.currentThread()); sink.drain(); });
            awaitWaiting(waiter);
            sink.abort(CancellationReason.CHAT_RESPONSE_TIMEOUT);
            drain.get(1, TimeUnit.SECONDS);
            Future<?> late = mock(Future.class);
            sink.registerResource(late);
            verify(late).cancel(true);
        }
        assertThat(control.cancellationReason()).isEqualTo(CancellationReason.CHAT_RESPONSE_TIMEOUT);
        verify(emitter).complete();
    }

    /** 没有发送任务时，队列中的业务确认也必须立即失败。 */
    @Test
    void shouldFailQueuedEventOnCompletionCallback() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        ChatExecutionControl control = new ChatExecutionControl();
        BoundedSseSink sink = new BoundedSseSink(emitter, control);
        AtomicReference<Thread> waiter = new AtomicReference<>();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> send = workers.submit(() -> { waiter.set(Thread.currentThread()); sink.send(event()); });
            awaitWaiting(waiter);
            sink.onCompletion();
            assertFailedQuickly(send);
        }
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(control.cancellationReason()).isEqualTo(CancellationReason.CHAT_CLIENT_DISCONNECTED);
    }

    /** 写入尚未返回时，关闭必须唤醒业务等待者，不能遗漏已经出队的确认。 */
    @Test
    void shouldFailInFlightAndQueuedEventsWithoutWaitingForWrite() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> { writing.countDown(); release.await(); return null; })
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        ChatExecutionControl control = new ChatExecutionControl();
        BoundedSseSink sink = new BoundedSseSink(emitter, control);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> drain = workers.submit(sink::drain);
            Future<?> first = workers.submit(() -> sink.send(event()));
            try {
                assertThat(writing.await(1, TimeUnit.SECONDS)).isTrue();
                AtomicReference<Thread> waiter = new AtomicReference<>();
                Future<?> second = workers.submit(() -> { waiter.set(Thread.currentThread()); sink.send(event()); });
                awaitWaiting(waiter);
                sink.abort(CancellationReason.CHAT_CLIENT_DISCONNECTED);
                assertFailedQuickly(first);
                assertFailedQuickly(second);
            } finally {
                release.countDown();
            }
            drain.get(1, TimeUnit.SECONDS);
        }
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    /** 满队列不阻碍关闭，正在等待入队的生产者立即结束。 */
    @Test
    void shouldWakeProducerWhenQueueIsFull() throws Exception {
        BoundedSseSink sink = new BoundedSseSink(mock(SseEmitter.class), new ChatExecutionControl(),
                1, Duration.ofSeconds(5));
        sink.heartbeat();
        AtomicReference<Thread> waiter = new AtomicReference<>();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> blocked = workers.submit(() -> { waiter.set(Thread.currentThread()); sink.send(event()); });
            awaitWaiting(waiter);
            sink.abort(CancellationReason.CHAT_CLIENT_DISCONNECTED);
            assertFailedQuickly(blocked);
        }
    }

    /** 入队超时与写入确认超时都必须报告慢客户端，而不是无限等待。 */
    @Test
    void shouldClassifyQueueAndConfirmationTimeouts() {
        for (boolean full : new boolean[] {false, true}) {
            ChatExecutionControl control = new ChatExecutionControl();
            BoundedSseSink sink = new BoundedSseSink(mock(SseEmitter.class), control,
                    1, Duration.ofMillis(20));
            if (full) sink.heartbeat();
            assertThatThrownBy(() -> sink.send(event())).isInstanceOf(IllegalStateException.class);
            assertThat(control.cancellationReason()).isEqualTo(CancellationReason.CHAT_CLIENT_TOO_SLOW);
        }
    }

    /** 业务发送异常必须同时使等待者失败并关闭连接，重复回调不重复清理。 */
    @Test
    void shouldAbortOnWriteFailure() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("synthetic failure")).when(emitter)
                .send(any(SseEmitter.SseEventBuilder.class));
        ChatExecutionControl control = new ChatExecutionControl();
        BoundedSseSink sink = new BoundedSseSink(emitter, control);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> send = workers.submit(() -> sink.send(event()));
            Future<?> drain = workers.submit(sink::drain);
            assertFailedQuickly(send);
            drain.get(1, TimeUnit.SECONDS);
        }
        sink.onCompletion();
        verify(emitter).complete();
        assertThat(control.isCancelled()).isTrue();
    }

    /** 心跳异常和单个资源清理异常不能留下其他心跳任务。 */
    @Test
    void shouldCleanOtherResourcesDespiteFailure() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("heartbeat failed")).when(emitter)
                .send(any(SseEmitter.SseEventBuilder.class));
        BoundedSseSink sink = new BoundedSseSink(emitter, new ChatExecutionControl());
        Future<?> broken = mock(Future.class);
        Future<?> heartbeat = mock(Future.class);
        doThrow(new IllegalStateException("synthetic cancel failure")).when(broken).cancel(false);
        sink.registerResource(broken);
        sink.registerResource(heartbeat);
        sink.heartbeat();
        sink.drain();
        verify(heartbeat).cancel(false);
        verify(emitter).complete();
    }

    /** 正常完成后晚注册的心跳也必须停止，但不触发中断取消。 */
    @Test
    void shouldStopLateResourceAfterNormalCompletion() {
        BoundedSseSink sink = new BoundedSseSink(mock(SseEmitter.class), new ChatExecutionControl());
        sink.complete();
        sink.drain();
        Future<?> late = mock(Future.class);
        sink.registerResource(late);
        verify(late).cancel(false);
    }

    /** 合成无敏感正文的事件，避免测试依赖网络或模型。 */
    private ChatEvent event() {
        return new ChatEvent(UUID.randomUUID(), "answer.started", UUID.randomUUID(), UUID.randomUUID(),
                1, Instant.EPOCH, Map.of());
    }

    /** 小于生产五秒超时地确认发送失败，防止测试把自然超时误算成即时唤醒。 */
    private void assertFailedQuickly(Future<?> task) {
        assertThatThrownBy(() -> task.get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(IllegalStateException.class);
    }

    /** 确认线程已经进入等待，不能把任务尚未开始的拒绝当作队列唤醒验证。 */
    private void awaitWaiting(AtomicReference<Thread> waiter) {
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
            assertThat(waiter.get()).isNotNull();
            assertThat(waiter.get().getState()).isIn(Thread.State.WAITING, Thread.State.TIMED_WAITING);
        });
    }
}
