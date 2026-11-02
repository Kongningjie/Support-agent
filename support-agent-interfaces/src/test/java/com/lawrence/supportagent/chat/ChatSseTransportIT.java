package com.lawrence.supportagent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.chat.port.ConversationStorePort.BeginResult;
import com.lawrence.supportagent.chat.port.ConversationStorePort.BeginStatus;
import com.lawrence.supportagent.user.UserRole;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 真实 Tomcat/MVC/SSE 传输测试；仅用例为合成桩，不调用存储、模型或认证生产配置。 */
class ChatSseTransportIT {
    @TempDir
    private Path temporary;
    private Tomcat tomcat;
    private AnnotationConfigWebApplicationContext application;
    private Probe probe;
    private int port;

    /** 在随机本机端口运行真实控制器，所有测试配置只存在于测试源集。 */
    @BeforeEach
    void startServer() throws Exception {
        tomcat = new Tomcat();
        tomcat.setBaseDir(temporary.toString());
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        Context context = tomcat.addContext("", temporary.toString());
        application = new AnnotationConfigWebApplicationContext();
        application.register(WebConfiguration.class);
        var servlet = Tomcat.addServlet(context, "chat", new DispatcherServlet(application));
        servlet.setAsyncSupported(true);
        servlet.setLoadOnStartup(1);
        context.addServletMappingDecoded("/", "chat");
        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
        probe = application.getBean(Probe.class);
    }

    /** 先允许合成业务退出再停止服务器，回收线程和临时上下文。 */
    @AfterEach
    void stopServer() throws Exception {
        if (probe != null) probe.stop.countDown();
        try {
            if (tomcat != null) tomcat.stop();
        } finally {
            if (application != null) application.close();
            if (tomcat != null) tomcat.destroy();
        }
    }

    /** 客户端收到流后主动 RST，真实发送失败必须在五秒内唤醒业务等待者。 */
    @Test
    void shouldStopSendingAfterRealClientDisconnect() throws Exception {
        try (Socket client = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
            client.setSoTimeout(5000);
            client.setSoLinger(true, 0);
            byte[] body = requestBody("streaming").getBytes(StandardCharsets.UTF_8);
            String headers = "POST /api/v1/chat/stream HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Type: application/json\r\nAccept: text/event-stream\r\n"
                    + "Content-Length: " + body.length + "\r\n\r\n";
            client.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().write(body);
            client.getOutputStream().flush();
            String first = new String(client.getInputStream().readNBytes(256), StandardCharsets.UTF_8);
            assertThat(first).contains("200", "text/event-stream");
        }
        assertThat(probe.disconnected.await(5, TimeUnit.SECONDS)).isTrue();
        // 另一条请求仍可正常完成，不能因断连关闭共享执行器。
        assertNormalResponse();
    }

    /** 正常真实响应保持顺序，正常完成不会被异常完成兜底误判。 */
    @Test
    void shouldCompleteNormalResponse() throws Exception {
        assertNormalResponse();
        assertThat(probe.disconnected.getCount()).isEqualTo(1);
    }

    /** 读取完整真实响应，确认开始及完成事件均已送达。 */
    private void assertNormalResponse() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/api/v1/chat/stream"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody("normal"))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("event:conversation.started", "event:answer.completed");
            assertThat(response.body().indexOf("event:conversation.started"))
                    .isLessThan(response.body().indexOf("event:answer.completed"));
        }
    }

    /** 构造仅用于测试路由的合成请求，不使用真实用户或模型正文。 */
    private String requestBody(String mode) {
        return """
                {"spaceId":"00000000-0000-0000-0000-000000000001",
                 "clientMessageId":"%s","message":"%s"}
                """.formatted(UUID.randomUUID(), mode);
    }

    /** 仅装配 MVC 和真实控制器；不扫描生产配置，也不修改生产安全边界。 */
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class WebConfiguration implements WebMvcConfigurer {
        /** 注册用于同步观测测试业务结束的探针。 */
        @Bean Probe probe() { return new Probe(); }
        /** 为真实控制器提供合成用例，隔离模型和数据库。 */
        @Bean ChatUseCase useCase(Probe probe) { return probe.useCase(); }
        /** 真实控制器管理实际发送与心跳线程。 */
        @Bean ChatController chatController(ChatUseCase useCase) { return new ChatController(useCase); }
        /** 与生产相同地解析认证注解，测试中没有登录凭据所以结果为空。 */
        @Override public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new AuthenticationPrincipalArgumentResolver());
        }
    }

    /** 合成持续进度事件探针，仅验证传输关闭，不假冒模型订阅已取消或存储已清理。 */
    static class Probe {
        private final CountDownLatch disconnected = new CountDownLatch(1);
        private final CountDownLatch stop = new CountDownLatch(1);

        /** 创建持续发送进度或正常完成的确定性测试用例。 */
        private ChatUseCase useCase() {
            ChatUseCase useCase = mock(ChatUseCase.class);
            when(useCase.prepare(any())).thenAnswer(invocation -> {
                ChatRequest request = invocation.getArgument(0);
                AuthenticatedUser actor = new AuthenticatedUser(UUID.randomUUID(), "transport-test", UserRole.USER);
                return new ChatUseCase.PreparedChat(new ChatRequest(actor, request.spaceId(), null,
                        request.clientMessageId(), request.message(), null), UUID.randomUUID(),
                        new BeginResult(BeginStatus.ACQUIRED, UUID.randomUUID(), 0, request.spaceId(), null, null), null);
            });
            doAnswer(invocation -> {
                ChatUseCase.PreparedChat prepared = invocation.getArgument(0);
                ChatEventSink sink = invocation.getArgument(1);
                try {
                    sink.send(event(prepared, "conversation.started", 1));
                    if ("normal".equals(prepared.request().message())) {
                        sink.send(event(prepared, "answer.completed", 2));
                        sink.complete();
                    } else {
                        long sequence = 2;
                        while (!stop.await(25, TimeUnit.MILLISECONDS)) {
                            sink.send(event(prepared, "retrieval.started", sequence++));
                        }
                    }
                } catch (IllegalStateException exception) {
                    disconnected.countDown();
                }
                return null;
            }).when(useCase).stream(any(ChatUseCase.PreparedChat.class), any(), any(ChatExecutionControl.class));
            return useCase;
        }

        /** 合成有足够字节刷出缓冲的事件，不包含真实知识或模型输出。 */
        private ChatEvent event(ChatUseCase.PreparedChat prepared, String type, long sequence) {
            return new ChatEvent(UUID.randomUUID(), type, prepared.runId(), prepared.begin().conversationId(),
                    sequence, Instant.EPOCH, Map.of("testPadding", "x".repeat(8192)));
        }
    }
}
