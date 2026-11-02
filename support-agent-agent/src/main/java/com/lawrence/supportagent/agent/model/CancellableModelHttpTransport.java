package com.lawrence.supportagent.agent.model;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.model.transport.HttpTransportFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/** 仅供 Chat/Intent 使用的非阻塞行订阅传输，取消当前请求不关闭共享客户端。 */
final class CancellableModelHttpTransport implements HttpTransport {
    private static final CancellableModelHttpTransport SHARED = createShared();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** 将共享传输登记到既有 SDK 关闭机制，不替换全局默认传输。 */
    private static CancellableModelHttpTransport createShared() {
        CancellableModelHttpTransport transport = new CancellableModelHttpTransport();
        HttpTransportFactory.register(transport);
        return transport;
    }

    /** 返回进程级共享传输，不为每次请求创建网络客户端。 */
    static CancellableModelHttpTransport shared() { return SHARED; }

    /** 非流式调用仍使用异步 HTTP；SDK 等待线程被中断时取消本次 future。 */
    @Override
    public HttpResponse execute(HttpRequest request) {
        var future = client.sendAsync(toJdkRequest(request),
                java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            var response = future.get();
            var result = HttpResponse.builder().statusCode(response.statusCode())
                    .body(response.statusCode() >= 200 && response.statusCode() < 300 ? response.body() : "");
            response.headers().map().forEach((name, values) -> result.header(name, String.join(",", values)));
            return result.build();
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new HttpTransportException("模型 HTTP 等待被中断", exception);
        } catch (ExecutionException exception) {
            throw new HttpTransportException("模型 HTTP 请求失败", exception.getCause());
        }
    }

    /** 由 JDK 逐行解码 UTF-8，不持有 BufferedReader 的阻塞读取锁。 */
    @Override
    public Flux<String> stream(HttpRequest request) {
        return Flux.create(sink -> {
            AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
            AtomicReference<CompletableFuture<?>> pending = new AtomicReference<>();
            AtomicBoolean terminated = new AtomicBoolean();
            sink.onDispose(() -> {
                terminated.set(true);
                Flow.Subscription body = subscription.getAndSet(null);
                if (body != null) body.cancel();
                CompletableFuture<?> future = pending.getAndSet(null);
                if (future != null) future.cancel(true);
            });
            SseLines lines = new SseLines(sink, subscription, terminated);
            var future = client.sendAsync(toJdkRequest(request), info -> {
                if (info.statusCode() < 200 || info.statusCode() >= 300) {
                    sink.error(new HttpTransportException("模型 HTTP 状态异常", info.statusCode(), null));
                }
                return java.net.http.HttpResponse.BodySubscribers.fromLineSubscriber(lines);
            });
            pending.set(future);
            if (terminated.get()) future.cancel(true);
            future.whenComplete((ignored, error) -> {
                if (error != null && !terminated.get()) sink.error(new HttpTransportException("模型 HTTP 流失败", error));
            });
        }, FluxSink.OverflowStrategy.ERROR);
    }

    /** 仅在 SDK 进程生命周期结束时关闭共享客户端，不得从请求取消路径调用。 */
    @Override public void close() { client.close(); }

    /** 保留 SDK 的地址、方法、头和正文；模型超时由调用层控制，传输另设五分钟兜底。 */
    private java.net.http.HttpRequest toJdkRequest(HttpRequest request) {
        var builder = java.net.http.HttpRequest.newBuilder(URI.create(request.getUrl()))
                .timeout(Duration.ofMinutes(5));
        request.getHeaders().forEach(builder::header);
        return builder.method(request.getMethod(), request.getBody() == null
                ? java.net.http.HttpRequest.BodyPublishers.noBody()
                : java.net.http.HttpRequest.BodyPublishers.ofString(request.getBody(), StandardCharsets.UTF_8)).build();
    }

    /** 只解析 SSE data 事件，支持分片、注释、多行 data 与 DONE；不记录任何正文。 */
    private static final class SseLines implements Flow.Subscriber<String> {
        private static final int MAX_EVENT_CHARACTERS = 1_048_576;
        private final FluxSink<String> sink;
        private final AtomicReference<Flow.Subscription> subscription;
        private final AtomicBoolean terminated;
        private final StringBuilder data = new StringBuilder();
        private boolean firstLine = true;

        /** 每个响应独享解析缓冲与订阅句柄。 */
        private SseLines(FluxSink<String> sink, AtomicReference<Flow.Subscription> subscription,
                         AtomicBoolean terminated) {
            this.sink = sink;
            this.subscription = subscription;
            this.terminated = terminated;
        }

        /** 处理取消先于响应体订阅的情况，不请求已经失效的响应。 */
        @Override public void onSubscribe(Flow.Subscription body) {
            subscription.set(body);
            if (terminated.get()) body.cancel();
            else body.request(Long.MAX_VALUE);
        }

        /** JDK 负责跨字节块 UTF-8 与行分隔；此处只解释已完整的行。 */
        @Override public void onNext(String line) {
            if (terminated.get()) return;
            if (firstLine) {
                firstLine = false;
                if (line.startsWith("\uFEFF")) line = line.substring(1);
            }
            if (line.isEmpty()) { emitEvent(); return; }
            if (!line.startsWith("data:")) return;
            String value = line.substring(5);
            if (value.startsWith(" ")) value = value.substring(1);
            if (data.length() + value.length() + 1 > MAX_EVENT_CHARACTERS) {
                sink.error(new HttpTransportException("模型 SSE 单事件超出限制"));
                return;
            }
            data.append(value).append('\n');
        }

        /** 响应体出错只传播安全摘要，不复制供应商正文。 */
        @Override public void onError(Throwable error) {
            if (!terminated.get()) sink.error(new HttpTransportException("模型响应体读取失败", error));
        }

        /** 支持没有最后空行的合法尾部事件，再结束本次响应。 */
        @Override public void onComplete() {
            if (!terminated.get()) { emitEvent(); sink.complete(); }
        }

        /** DONE 立即完成并取消本次请求；多行 data 用换行连接。 */
        private void emitEvent() {
            if (data.isEmpty()) return;
            String event = data.substring(0, data.length() - 1);
            data.setLength(0);
            if (event.isBlank()) return;
            if ("[DONE]".equals(event)) sink.complete();
            else sink.next(event);
        }
    }
}
