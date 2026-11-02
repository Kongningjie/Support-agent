package com.lawrence.supportagent.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.lawrence.supportagent.chat.ChatExecutionControl;
import com.lawrence.supportagent.chat.ChatCancellationException;
import com.lawrence.supportagent.model.ModelInvocationSecurity;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 验证批准的传输补齐：取消订阅必须关闭 HTTP 请求，不弱化此前失败场景的断言。 */
class AgentScopeCancellationTransportIT {
    /** 首 Token 前取消时，本地等待和服务端连接都必须及时结束。 */
    @Test
    void shouldCloseTransportBeforeFirstToken() throws Exception {
        verifyTransportCancellation(false);
    }

    /** 已收到首 Token 后停止供应商发送，再取消以验证阻塞读取也能解除。 */
    @Test
    void shouldCloseTransportDuringIdleStream() throws Exception {
        verifyTransportCancellation(true);
    }

    /** 问候首 Token 前的显式控制能关闭实际请求。 */
    @Test void shouldCancelGreetingBeforeFirstToken() throws Exception { verifyCancellation(false, "greeting"); }
    /** 问候生成途中取消不得返回已缓冲的部分答案。 */
    @Test void shouldCancelGreetingDuringIdleStream() throws Exception { verifyCancellation(true, "greeting"); }
    /** 知识模型使用相同传输取消，不包装为模型不可用。 */
    @Test void shouldCancelGroundedDuringIdleStream() throws Exception { verifyCancellation(true, "grounded"); }
    /** 非流式意图模型在等待响应时也必须关闭上游请求。 */
    @Test void shouldCancelIntentBeforeResponse() throws Exception { verifyCancellation(false, "intent"); }
    /** 工单 Agent 顶层执行取消必须传到实际模型流。 */
    @Test void shouldCancelTicketAgentDuringIdleStream() throws Exception { verifyCancellation(true, "ticket"); }

    /** 用原始本机 HTTP/1.1 socket 观察 EOF，避免只把一次写失败当成关闭证据。 */
    private void verifyTransportCancellation(boolean firstToken) throws Exception {
        verifyCancellation(firstToken, "direct");
    }

    /** 同一强度的 socket EOF 断言用于 SDK 直连和实际应用适配器。 */
    private void verifyCancellation(boolean firstToken, String branch) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
             var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch requested = new CountDownLatch(1);
            CountDownLatch received = new CountDownLatch(1);
            var peer = tasks.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(10000);
                    InputStream input = socket.getInputStream();
                    int length = 0;
                    String line;
                    while (!(line = readLine(input)).isEmpty()) {
                        if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                            length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                        }
                    }
                    input.readNBytes(length);
                    if (firstToken) {
                        OutputStream output = socket.getOutputStream();
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                                + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                        String chunk = "data: {\"id\":\"fixture\",\"object\":\"chat.completion.chunk\","
                                + "\"created\":0,\"model\":\"test\",\"choices\":[{\"index\":0,"
                                + "\"delta\":{\"role\":\"assistant\",\"content\":\"test\"}}]}\n\n";
                        byte[] bytes = chunk.getBytes(StandardCharsets.UTF_8);
                        output.write((Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                        output.write(bytes);
                        output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                        output.flush();
                    }
                    requested.countDown();
                    return input.read();
                }
            });
            String base = "http://127.0.0.1:" + server.getLocalPort();
            ChatExecutionControl control = new ChatExecutionControl();
            java.util.concurrent.Future<?> result;
            Runnable cancel;
            if ("direct".equals(branch)) {
                var model = OpenAIChatModel.builder().apiKey("test-key").modelName("test")
                        .httpTransport(CancellableModelHttpTransport.shared()).baseUrl(base).stream(true).build();
                var subscription = model.stream(List.of(Msg.builder().role(MsgRole.USER).textContent("fixture").build()),
                        List.of(), null).subscribe(value -> received.countDown(), ignored -> { });
                cancel = subscription::dispose;
                result = null;
            } else {
                var chat = new DashScopeChatModelAdapter("test-key", "test", base);
                result = tasks.submit(() -> switch (branch) {
                    case "greeting" -> chat.greeting("fixture", List.of(), received::countDown,
                            ModelInvocationSecurity.none(), control);
                    case "grounded" -> chat.groundedAnswer("fixture", List.of(), List.of(), received::countDown,
                            ModelInvocationSecurity.none(), control);
                    case "intent" -> new DashScopeIntentRecognitionAdapter("test-key", "test", base)
                            .recognize("fixture", List.of(), control);
                    case "ticket" -> chat.ticketAnswer("fixture", "T123456789012", null, List.of(),
                            received::countDown, ModelInvocationSecurity.none(), control);
                    default -> throw new AssertionError("unknown fixture branch");
                });
                cancel = () -> control.cancel(ChatExecutionControl.CancellationReason.CHAT_CLIENT_DISCONNECTED);
            }
            try {
                assertThat(requested.await(5, TimeUnit.SECONDS)).isTrue();
                if (firstToken && !"ticket".equals(branch)) assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
                var cancelled = tasks.submit(cancel);
                cancelled.get(5, TimeUnit.SECONDS);
                if (result != null) assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(ChatCancellationException.class);
                if (result != null) {
                    assertThat(control.upstreamCancellationCount(com.lawrence.supportagent.observability.OptimizationTelemetryPort.UpstreamResult.REQUESTED)).isEqualTo(1);
                    assertThat(control.upstreamCancellationCount(com.lawrence.supportagent.observability.OptimizationTelemetryPort.UpstreamResult.LOCAL_RELEASED)).isEqualTo(1);
                    assertThat(control.upstreamCancellationCount(com.lawrence.supportagent.observability.OptimizationTelemetryPort.UpstreamResult.RELEASE_FAILED)).isZero();
                }
                assertThat(peer.get(5, TimeUnit.SECONDS)).isEqualTo(-1);
            } finally {
                cancel.run();
            }
        }
    }

    /** 仅解析 ASCII HTTP 头，保证正文按字节准确消费。 */
    private String readLine(InputStream input) throws java.io.IOException {
        StringBuilder line = new StringBuilder();
        int next;
        while ((next = input.read()) != -1 && next != '\n') {
            if (next != '\r') line.append((char) next);
        }
        if (next == -1) throw new java.io.EOFException("fixture request closed before headers");
        return line.toString();
    }
}
