package com.lawrence.supportagent.chat;

import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 暴露具有有界缓冲、心跳和慢客户端保护的聊天 SSE 接口。 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
    private static final int PENDING_EVENT_CAPACITY = 32;
    private static final long CLIENT_SEND_TIMEOUT_SECONDS = 5;
    private static final long HEARTBEAT_INTERVAL_SECONDS = 15;
    private static final Duration RESPONSE_TIMEOUT = Duration.ofMinutes(5);
    private final ChatUseCase useCase;
    private final ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("chat-heartbeat-", 0).factory());

    /** 注入聊天应用用例。 */
    public ChatController(ChatUseCase useCase) { this.useCase = useCase; }

    /** 接收一个用户消息并返回独立 SSE 事件协议。 */
    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatStreamRequest request) {
        ChatUseCase.PreparedChat prepared = useCase.prepare(request.toCommand());
        SseEmitter emitter = new SseEmitter(RESPONSE_TIMEOUT.toMillis());
        BoundedSseSink sink = new BoundedSseSink(emitter);
        streams.execute(() -> sink.drain());
        streams.execute(() -> useCase.stream(prepared, sink));
        var heartbeat = heartbeats.scheduleAtFixedRate(sink::heartbeat,
                HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        emitter.onCompletion(() -> heartbeat.cancel(false));
        emitter.onTimeout(() -> { heartbeat.cancel(false); sink.abort(); });
        emitter.onError(ignored -> { heartbeat.cancel(false); sink.abort(); });
        return emitter;
    }

    /** 关闭接口层拥有的流执行器和心跳调度器。 */
    @PreDestroy
    public void close() {
        streams.close();
        heartbeats.close();
    }

    /** 用最多 32 个待发事件隔离业务生产速度和客户端消费速度。 */
    private static final class BoundedSseSink implements ChatEventSink {
        private static final Object HEARTBEAT = new Object();
        private static final Object COMPLETE = new Object();
        private final SseEmitter emitter;
        private final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(PENDING_EVENT_CAPACITY);
        private volatile boolean closed;

        /** 创建绑定单个响应的发送队列。 */
        private BoundedSseSink(SseEmitter emitter) { this.emitter = emitter; }
        /** {@inheritDoc} */
        @Override public void send(ChatEvent event) { sendAndAwait(event); }
        /** {@inheritDoc} */
        @Override public void heartbeat() { if (!closed) queue.offer(HEARTBEAT); }
        /** {@inheritDoc} */
        @Override public void complete() { offer(COMPLETE); }

        /** 在单一发送线程中保持业务事件顺序。 */
        private void drain() {
            try {
                while (!closed) {
                    Object item = queue.take();
                    if (item == COMPLETE) { closed = true; emitter.complete(); return; }
                    if (item == HEARTBEAT) emitter.send(SseEmitter.event().comment("heartbeat"));
                    else {
                        PendingEvent pending = (PendingEvent) item;
                        ChatEvent event = pending.event();
                        try {
                            emitter.send(SseEmitter.event().id(event.eventId().toString())
                                    .name(event.eventType()).data(event));
                            pending.sent().complete(null);
                        } catch (IOException | RuntimeException exception) {
                            pending.sent().completeExceptionally(exception);
                            throw exception;
                        }
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt(); abort();
            } catch (IOException | RuntimeException exception) {
                abort();
            }
        }

        /** 最多等待五秒入队，超时即把该客户端判定为过慢。 */
        private void offer(Object value) {
            if (closed) throw new IllegalStateException("SSE 连接已经关闭");
            try {
                if (!queue.offer(value, CLIENT_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    abort();
                    throw new IllegalStateException("SSE 客户端消费过慢");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                abort();
                throw new IllegalStateException("SSE 发送被中断", exception);
            }
        }

        /** 等待单个业务事件真实写入，五秒超时视为慢客户端。 */
        private void sendAndAwait(ChatEvent event) {
            PendingEvent pending = new PendingEvent(event, new CompletableFuture<>());
            offer(pending);
            try {
                pending.sent().get(CLIENT_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt(); abort();
                throw new IllegalStateException("SSE 发送被中断", exception);
            } catch (ExecutionException | TimeoutException exception) {
                abort();
                throw new IllegalStateException("SSE 客户端断开或消费过慢", exception);
            }
        }

        /** 中止响应并拒绝后续事件。 */
        private void abort() {
            if (!closed) { closed = true; emitter.complete(); }
        }

        /** 保存待发送业务事件和真实写入确认。 */
        private record PendingEvent(ChatEvent event, CompletableFuture<Void> sent) { }
    }
}
