package com.lawrence.supportagent.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.lawrence.supportagent.chat.ChatCancellationException;
import com.lawrence.supportagent.chat.ChatExecutionControl;
import com.lawrence.supportagent.chat.ChatIntent;
import com.lawrence.supportagent.model.ModelInvocationSecurity;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 通过真实本地 HTTP 验证 SSE 编码、结束、错误、非流式意图和并行请求隔离。 */
class CancellableModelProtocolIT {
    private final ObjectMapper json = new ObjectMapper();

    /** UTF-8 跨字节块、CRLF、多行 data、注释和末尾无空行都不能损坏答案。 */
    @Test void shouldDecodeFragmentedUnicodeAndFinalEvent() throws Exception {
        for (boolean done : new boolean[] {true, false}) {
            HttpServer server = server();
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                beginSse(exchange);
                String event = chunk("你好🙂");
                String object = event.substring(6).trim();
                int split = object.indexOf("\"choices\"");
                String reply = "\uFEFF: heartbeat\r\n\r\ndata:\r\n\r\ndata: " + object.substring(0, split)
                        + "\r\ndata: " + object.substring(split) + (done ? "\r\n\r\ndata: [DONE]\r\n\r\n" : "");
                try (var output = exchange.getResponseBody()) {
                    byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                    for (byte next : bytes) { output.write(next); output.flush(); }
                }
            });
            server.start();
            try {
                AtomicInteger first = new AtomicInteger();
                var answer = chat(server, "fixture").greeting("fixture", List.of(), first::incrementAndGet);
                assertThat(answer.text()).isEqualTo("你好🙂");
                assertThat(first.get()).isEqualTo(1);
            } finally { server.stop(0); }
        }
    }

    /** 非流式意图响应继续严格解析；HTTP 500 不自动重试。 */
    @Test void shouldHandleIntentAndRejectHttpErrorWithoutRetry() throws Exception {
        HttpServer server = server();
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.incrementAndGet();
            if (body.contains("intent-test")) {
                byte[] response = json.writeValueAsBytes(Map.of("id", "fixture", "object", "chat.completion",
                        "created", 0, "model", "intent-test", "choices", List.of(Map.of("index", 0,
                        "finish_reason", "stop", "message", Map.of("role", "assistant", "content",
                        "INTENT=SUPPORT_QUERY CONFIDENCE=0.95 QUERY=fixture TICKET=NONE")))));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (var output = exchange.getResponseBody()) { output.write(response); }
            } else {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            }
        });
        server.start();
        try {
            var intent = new DashScopeIntentRecognitionAdapter("test-key", "intent-test", base(server))
                    .recognize("fixture", List.of(), new ChatExecutionControl());
            assertThat(intent.intent()).isEqualTo(ChatIntent.SUPPORT_QUERY);
            assertThatThrownBy(() -> chat(server, "error-test").greeting("fixture", List.of(), () -> { }))
                    .isInstanceOf(com.lawrence.supportagent.model.ModelInvocationException.class);
            assertThat(requests.get()).isEqualTo(2);
        } finally { server.stop(0); }
    }

    /** 取消一个挂起请求后，另一已在执行的请求仍能收到完整答案。 */
    @Test void shouldKeepParallelRequestAliveAfterCancellation() throws Exception {
        HttpServer server = server();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch idleFirst = new CountDownLatch(1);
        CountDownLatch normalFirst = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(threads);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                requests.incrementAndGet();
                beginSse(exchange);
                try (var output = exchange.getResponseBody()) {
                    output.write(chunk("正常").getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IOException("fixture release timeout");
                    output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (IOException exception) {
                    // 已取消客户端关闭连接属于此隔离测试的预期清理；EOF 由独立 raw socket IT 验证。
                    exchange.close();
                }
            });
            server.start();
            ChatExecutionControl cancelled = new ChatExecutionControl();
            ChatExecutionControl normal = new ChatExecutionControl();
            try {
                var adapter = chat(server, "fixture");
                var idle = threads.submit(() -> adapter.greeting("fixture", List.of(), idleFirst::countDown,
                        ModelInvocationSecurity.none(), cancelled));
                var other = threads.submit(() -> adapter.greeting("fixture", List.of(), normalFirst::countDown,
                        ModelInvocationSecurity.none(), normal));
                assertThat(idleFirst.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(normalFirst.await(5, TimeUnit.SECONDS)).isTrue();
                cancelled.cancel(ChatExecutionControl.CancellationReason.CHAT_CLIENT_DISCONNECTED);
                assertThatThrownBy(() -> idle.get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(ChatCancellationException.class);
                release.countDown();
                assertThat(other.get(5, TimeUnit.SECONDS).text()).isEqualTo("正常");
                assertThat(normal.isCancelled()).isFalse();
                assertThat(requests.get()).isEqualTo(2);
            } finally { release.countDown(); server.stop(0); }
        }
    }

    /** 真实 Agent 已进入工具后取消，不得再发下一轮推理请求。 */
    @Test void shouldStopAgentBetweenToolAndNextInference() throws Exception {
        HttpServer server = server();
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            beginSse(exchange);
            String call = json.writeValueAsString(Map.of("id", "fixture", "object", "chat.completion.chunk",
                    "created", 0, "model", "fixture", "choices", List.of(Map.of("index", 0,
                    "delta", Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0,
                    "id", "fixture-call", "type", "function", "function", Map.of("name", "get_ticket",
                    "arguments", "{\"ticketNo\":\"T123456789012\"}"))))))));
            String finish = json.writeValueAsString(Map.of("id", "fixture", "object", "chat.completion.chunk",
                    "created", 0, "model", "fixture", "choices", List.of(Map.of("index", 0,
                    "delta", Map.of(), "finish_reason", "tool_calls"))));
            try (var output = exchange.getResponseBody()) {
                output.write(("data: " + call + "\n\ndata: " + finish + "\n\ndata: [DONE]\n\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        ChatExecutionControl control = new ChatExecutionControl();
        var ticket = org.mockito.Mockito.mock(com.lawrence.supportagent.ticket.TicketDetails.class);
        org.mockito.Mockito.when(ticket.title()).thenAnswer(invocation -> {
            control.cancel(ChatExecutionControl.CancellationReason.CHAT_CLIENT_DISCONNECTED);
            return "fixture";
        });
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = threads.submit(() -> chat(server, "fixture").ticketAnswer("fixture", "T123456789012",
                    ticket, List.of(), () -> { }, ModelInvocationSecurity.none(), control));
            try {
                assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(ChatCancellationException.class);
                org.mockito.Mockito.verify(ticket).title();
                assertThat(requests.get()).isEqualTo(1);
            } finally { control.cancel(ChatExecutionControl.CancellationReason.CHAT_CLIENT_DISCONNECTED); }
        } finally { server.stop(0); }
    }

    /** 创建只监听本机随机端口的测试服务。 */
    private HttpServer server() throws IOException { return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); }
    /** 真实模型适配器使用显式合成凭据，不读取环境变量。 */
    private DashScopeChatModelAdapter chat(HttpServer server, String name) {
        return new DashScopeChatModelAdapter("test-key", name, base(server));
    }
    /** 返回当前服务 URL，不访问外网。 */
    private String base(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    /** 初始化 chunked SSE 响应。 */
    private void beginSse(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, 0);
    }
    /** 生成最小兼容 SSE 数据块，仅包含合成正文。 */
    private String chunk(String text) {
        return "data: " + json.writeValueAsString(Map.of("id", "fixture", "object", "chat.completion.chunk",
                "created", 0, "model", "fixture", "choices", List.of(Map.of("index", 0,
                "delta", Map.of("role", "assistant", "content", text))))) + "\n\n";
    }
}
