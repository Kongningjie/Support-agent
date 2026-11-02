package com.lawrence.supportagent.chat;

import com.lawrence.supportagent.chat.ChatExecutionControl.CancellationReason;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 单响应有界发送通道；终止在队列之外表达，满队列也不会丢失关闭通知。 */
final class BoundedSseSink implements ChatEventSink {
    private static final System.Logger LOGGER = System.getLogger(BoundedSseSink.class.getName());
    private static final Object HEARTBEAT = new Object();
    private static final int DEFAULT_PENDING_EVENT_CAPACITY = 32;
    private static final Duration DEFAULT_SEND_TIMEOUT = Duration.ofSeconds(5);
    private final Object monitor = new Object();
    private final ArrayDeque<Object> queue = new ArrayDeque<>();
    private final Set<PendingEvent> unconfirmed = new HashSet<>();
    private final List<Future<?>> resources = new ArrayList<>();
    private final SseEmitter emitter;
    private final ChatExecutionControl control;
    private final int capacity;
    private final Duration sendTimeout;
    private boolean finishing;
    private boolean closed;
    private boolean normallyCompleted;
    private Thread drainThread;

    /** 使用线上固定的 32 个待发事件和五秒保护期限。 */
    BoundedSseSink(SseEmitter emitter, ChatExecutionControl control) {
        this(emitter, control, DEFAULT_PENDING_EVENT_CAPACITY, DEFAULT_SEND_TIMEOUT);
    }

    /** 注入有界参数以验证超时边界，生产入口不暴露调参配置。 */
    BoundedSseSink(SseEmitter emitter, ChatExecutionControl control, int capacity, Duration sendTimeout) {
        if (capacity <= 0 || sendTimeout.isZero() || sendTimeout.isNegative()) {
            throw new IllegalArgumentException("发送容量和超时必须为正数");
        }
        this.emitter = emitter;
        this.control = control;
        this.capacity = capacity;
        this.sendTimeout = sendTimeout;
    }

    /** 注册当前响应的发送任务或心跳；关闭先发生时立即停止晚注册资源。 */
    void registerResource(Future<?> resource) {
        boolean interrupt;
        synchronized (monitor) {
            if (!closed) { resources.add(resource); return; }
            interrupt = !normallyCompleted;
        }
        stopResource(resource, interrupt);
    }

    /** 等待业务事件真实写入；关闭时连同已出队事件一起立即通知失败。 */
    @Override
    public void send(ChatEvent event) {
        PendingEvent pending = new PendingEvent(event, new CompletableFuture<>());
        try {
            enqueue(pending);
            pending.sent().get(sendTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            abort(CancellationReason.CHAT_CLIENT_DISCONNECTED);
            throw new IllegalStateException("SSE 发送被中断", exception);
        } catch (TimeoutException exception) {
            abort(CancellationReason.CHAT_CLIENT_TOO_SLOW);
            throw new IllegalStateException("SSE 客户端消费过慢", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("SSE 连接已经关闭", exception.getCause());
        }
    }

    /** 最多等待一个保护期限入队；终止状态与入队在同一锁内协调。 */
    private void enqueue(PendingEvent pending) throws InterruptedException, TimeoutException {
        long deadline = System.nanoTime() + sendTimeout.toNanos();
        synchronized (monitor) {
            while (!closed && !finishing && queue.size() >= capacity) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException("SSE 队列已满");
                TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
            }
            if (closed || finishing) throw new IllegalStateException("SSE 通道已停止接收事件");
            queue.addLast(pending);
            unconfirmed.add(pending);
            monitor.notifyAll();
        }
    }

    /** 心跳不阻塞调度线程；队列已满时等待现有业务事件的慢客户端保护生效。 */
    @Override
    public void heartbeat() {
        synchronized (monitor) {
            if (!closed && !finishing && queue.size() < capacity) {
                queue.addLast(HEARTBEAT);
                monitor.notifyAll();
            }
        }
    }

    /** 停止接收新事件，发送完已接受队列后正常完成，不占用队列容量。 */
    @Override
    public void complete() {
        synchronized (monitor) {
            if (closed) return;
            finishing = true;
            monitor.notifyAll();
        }
    }

    /** 区分主动正常完成与容器的异常完成回调。 */
    void onCompletion() {
        synchronized (monitor) {
            if (normallyCompleted) return;
        }
        abort(CancellationReason.CHAT_CLIENT_DISCONNECTED);
    }

    /** 单个发送线程保持顺序，出队后继续跟踪等待确认的事件。 */
    void drain() {
        synchronized (monitor) { drainThread = Thread.currentThread(); }
        try {
            while (true) {
                Object item;
                synchronized (monitor) {
                    while (!closed && queue.isEmpty() && !finishing) monitor.wait();
                    if (closed) return;
                    if (queue.isEmpty()) break;
                    item = queue.removeFirst();
                    monitor.notifyAll();
                }
                write(item);
            }
            terminate(null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            abort(CancellationReason.CHAT_CLIENT_DISCONNECTED);
        } catch (IOException | RuntimeException exception) {
            abort(CancellationReason.CHAT_CLIENT_DISCONNECTED);
        } finally {
            synchronized (monitor) { drainThread = null; }
        }
    }

    /** 只在发送线程写出；写入与关闭竞争时不能把已经失败的确认改成成功。 */
    private void write(Object item) throws IOException {
        if (item == HEARTBEAT) {
            emitter.send(SseEmitter.event().comment("heartbeat"));
            return;
        }
        PendingEvent pending = (PendingEvent) item;
        ChatEvent event = pending.event();
        emitter.send(SseEmitter.event().id(event.eventId().toString())
                .name(event.eventType()).data(event));
        synchronized (monitor) {
            if (!closed) pending.sent().complete(null);
            unconfirmed.remove(pending);
        }
    }

    /** 中止当前响应并记录首次原因；重复回调不会影响其他响应。 */
    void abort(CancellationReason reason) { terminate(reason); }

    /** 原子关闭队列、唤醒等待者，再在锁外取消资源和完成响应。 */
    private void terminate(CancellationReason reason) {
        List<Future<?>> stopped;
        boolean interrupt;
        synchronized (monitor) {
            if (closed) return;
            closed = true;
            normallyCompleted = reason == null;
            if (reason != null) control.cancel(reason);
            IllegalStateException failure = new IllegalStateException("SSE 连接已经关闭");
            unconfirmed.forEach(pending -> pending.sent().completeExceptionally(failure));
            unconfirmed.clear();
            queue.clear();
            monitor.notifyAll();
            stopped = List.copyOf(resources);
            resources.clear();
            interrupt = reason != null && Thread.currentThread() != drainThread;
        }
        stopped.forEach(resource -> stopResource(resource, interrupt));
        try {
            emitter.complete();
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "SSE 响应完成失败，本地发送通道已经关闭");
        }
    }

    /** 单个资源停止失败不能跳过其余资源；不记录可能包含外部正文的异常信息。 */
    private void stopResource(Future<?> resource, boolean interrupt) {
        try {
            resource.cancel(interrupt);
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "SSE 后台资源停止失败");
        }
    }

    /** 跟踪待写入事件及确认，直到成功写入或通道终止。 */
    private record PendingEvent(ChatEvent event, CompletableFuture<Void> sent) { }
}
