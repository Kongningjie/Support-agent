package com.lawrence.supportagent.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.lawrence.supportagent.chat.ChatCancellationException;
import com.lawrence.supportagent.chat.ChatExecutionControl;
import com.lawrence.supportagent.chat.ChatExecutionControl.CancellationReason;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** 不依赖网络验证请求取消、真实订阅取消和异常语义。 */
class CancellableModelCallTest {
    /** 网络错误与连接取消竞争时优先保留取消，不能包装成普通模型故障。 */
    @Test void shouldPreferCancellationOverConcurrentSourceFailure() {
        ChatExecutionControl control = new ChatExecutionControl();
        assertThatThrownBy(() -> CancellableModelCall.awaitLast(() -> {
            control.cancel(CancellationReason.CHAT_CLIENT_DISCONNECTED);
            throw new IllegalStateException("synthetic network failure");
        }, control, Duration.ofSeconds(1))).isInstanceOf(ChatCancellationException.class);
    }
    /** 已取消不能创建请求。 */
    @Test void shouldRejectBeforeCreatingPublisher() {
        ChatExecutionControl control = new ChatExecutionControl();
        control.cancel(CancellationReason.CHAT_CLIENT_DISCONNECTED);
        assertThatThrownBy(() -> CancellableModelCall.awaitLast(() -> {
            throw new AssertionError("不得创建请求");
        }, control, Duration.ofSeconds(5))).isInstanceOf(ChatCancellationException.class);
    }

    /** 挂起在首 Token 前时仍能终止等待和底层订阅，不返回空答案。 */
    @Test void shouldCancelRealSubscriptionAndWakeWaiter() throws Exception {
        ChatExecutionControl control = new ChatExecutionControl();
        CountDownLatch subscribed = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = threads.submit(() -> CancellableModelCall.awaitLast(() -> Flux.create(sink -> {
                sink.onCancel(() -> cancelled.set(true));
                subscribed.countDown();
            }), control, Duration.ofSeconds(30)));
            assertThat(subscribed.await(1, TimeUnit.SECONDS)).isTrue();
            control.cancel(CancellationReason.CHAT_RESPONSE_TIMEOUT);
            assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ChatCancellationException.class);
            assertThat(cancelled.get()).isTrue();
        }
    }

    /** 正常返回后注销动作，后续取消不触碰已完成调用。 */
    @Test void shouldCompleteAndKeepTimeoutAsFallback() {
        ChatExecutionControl control = new ChatExecutionControl();
        assertThat(CancellableModelCall.awaitLast(() -> Flux.just("ok"), control, Duration.ofSeconds(1)))
                .isEqualTo("ok");
        AtomicBoolean cancelled = new AtomicBoolean();
        assertThatThrownBy(() -> CancellableModelCall.awaitLast(
                () -> Flux.never().doOnCancel(() -> cancelled.set(true)), control, Duration.ofMillis(20)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(cancelled.get()).isTrue();
        control.cancel(CancellationReason.CHAT_CLIENT_DISCONNECTED);
    }
}
