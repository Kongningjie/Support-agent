package com.lawrence.supportagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.agent.model.DashScopeChatModelAdapter;
import com.lawrence.supportagent.chat.ChatExecutionControl;
import com.lawrence.supportagent.chat.port.ConversationStorePort;
import com.lawrence.supportagent.model.ChatModelPort;
import com.lawrence.supportagent.model.ModelInvocationSecurity;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/** 真实认证、SSE、业务编排、模型 HTTP 适配、Redis 和 MySQL 的断连清理集成测试。 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = SupportAgentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"support-agent.dashscope.api-key=", "support-agent.async-task.enabled=false",
                "support-agent.elasticsearch.url=http://127.0.0.1:1"})
class ChatDisconnectCleanupIT {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11")
            .withDatabaseName("support_agent").withUsername("support_agent").withPassword("test_password");
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4.7")).withExposedPorts(6379);
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private ConversationStorePort conversations;
    @Autowired private StringRedisTemplate redis;
    @MockitoBean(name = "chatModelPort") private ChatModelPort model;

    /** 全部存储绑定临时容器，认证凭据仅用于本测试。 */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        registry.add("support-agent.auth.bootstrap-admin.enabled", () -> "true");
        registry.add("support-agent.auth.bootstrap-admin.username", () -> "disconnect-admin");
        registry.add("support-agent.auth.bootstrap-admin.display-name", () -> "Disconnect Test");
        registry.add("support-agent.auth.bootstrap-admin.password", () -> "disconnect-test-password");
    }

    /** 模型尚未返回时关闭浏览器连接；检测后五秒内必须关闭上游并完成 Redis 与 MySQL 清理。 */
    @Test
    void shouldCancelActualModelRequestAndReleaseRunAfterClientDisconnect() throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicLong cancellationTime = new AtomicLong();
        UUID messageId = UUID.randomUUID();
        try (ServerSocket upstream = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
             var tasks = Executors.newVirtualThreadPerTaskExecutor();
             HttpClient http = HttpClient.newHttpClient()) {
            upstream.setSoTimeout(10000);
            var peer = tasks.submit(() -> {
                try (Socket socket = upstream.accept()) {
                    socket.setSoTimeout(25000);
                    InputStream input = socket.getInputStream();
                    int length = 0;
                    String line;
                    while (!(line = readLine(input)).isEmpty()) {
                        if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                            length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                        }
                    }
                    input.readNBytes(length);
                    requested.countDown();
                    return input.read();
                }
            });
            var adapter = new DashScopeChatModelAdapter("local-test-key", "fixture",
                    "http://127.0.0.1:" + upstream.getLocalPort());
            when(model.greeting(any(), any(), any(), any(), any())).thenAnswer(call -> {
                ChatExecutionControl control = call.getArgument(4);
                try (var registration = control.register(() -> {
                    cancellationTime.set(System.nanoTime());
                    cancelled.countDown();
                })) {
                    return adapter.greeting(call.getArgument(0), call.getArgument(1), call.getArgument(2),
                            call.getArgument(3, ModelInvocationSecurity.class), control);
                }
            });
            String token = login(http);
            try (Socket browser = new Socket("127.0.0.1", port)) {
                browser.setSoTimeout(5000);
                browser.setSoLinger(true, 0);
                byte[] body = ("{\"spaceId\":\"00000000-0000-0000-0000-000000000001\","
                        + "\"clientMessageId\":\"" + messageId + "\",\"message\":\"你好\"}")
                        .getBytes(StandardCharsets.UTF_8);
                String headers = "POST /api/v1/chat/stream HTTP/1.1\r\nHost: localhost\r\n"
                        + "Authorization: Bearer " + token + "\r\nContent-Type: application/json\r\n"
                        + "Accept: text/event-stream\r\nContent-Length: " + body.length + "\r\n\r\n";
                browser.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                browser.getOutputStream().write(body);
                browser.getOutputStream().flush();
                StringBuilder response = new StringBuilder();
                String line;
                while (!(line = readLine(browser.getInputStream())).isEmpty()) response.append(line).append('\n');
                assertThat(response.toString()).contains("200", "text/event-stream");
                assertThat(requested.await(5, TimeUnit.SECONDS)).isTrue();
            }
            // TCP 断连由真实发送/十五秒心跳发现；五秒门槛从服务器收到取消通知开始。
            assertThat(cancelled.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(peer.get(5, TimeUnit.SECONDS)).isEqualTo(-1);
            java.util.Map<String, Object> row;
            do {
                row = jdbc.queryForMap("SELECT BIN_TO_UUID(user_id) AS user_id,"
                        + "BIN_TO_UUID(conversation_id) AS conversation_id,status,error_code FROM agent_run "
                        + "WHERE client_message_id=UUID_TO_BIN(?)", messageId.toString());
                if ("FAILED".equals(row.get("status"))) break;
                TimeUnit.MILLISECONDS.sleep(25);
            } while (System.nanoTime() - cancellationTime.get() < TimeUnit.SECONDS.toNanos(5));
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(row.get("error_code")).isEqualTo("CHAT_CLIENT_DISCONNECTED");
            assertThat(System.nanoTime() - cancellationTime.get()).isLessThan(TimeUnit.SECONDS.toNanos(5));
            UUID owner = UUID.fromString(row.get("user_id").toString());
            UUID conversationId = UUID.fromString(row.get("conversation_id").toString());
            String key = "support-agent:chat:conversation:{" + conversationId + "}";
            assertThat(redis.opsForHash().get(key, "status")).isEqualTo("IDLE");
            assertThat(redis.opsForHash().hasKey(key, "activeRunId")).isFalse();
            assertThat(redis.opsForHash().hasKey(key, "runLeaseUntil")).isFalse();
            var snapshot = conversations.lifecycleDetails(owner, false, conversationId, 20, Instant.now());
            assertThat(snapshot.version()).isZero();
            assertThat(snapshot.turns()).isEmpty();
            UUID nextRun = UUID.randomUUID();
            assertThat(conversations.begin(owner, conversationId, messageId, "你好", 0L, nextRun, Instant.now()).status())
                    .isEqualTo(ConversationStorePort.BeginStatus.ACQUIRED);
            conversations.fail(owner, conversationId, nextRun, Instant.now());
            verify(model, times(1)).greeting(any(), any(), any(), any(), any());
        }
    }

    /** 使用真实认证接口获取隔离管理员令牌，不读取本地配置或密钥。 */
    private String login(HttpClient client) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"disconnect-admin\","
                        + "\"password\":\"disconnect-test-password\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).path("data").path("accessToken").stringValue();
    }

    /** 仅解析本机合成 HTTP 请求的 ASCII 头，正文按长度消费。 */
    private String readLine(InputStream input) throws java.io.IOException {
        StringBuilder line = new StringBuilder();
        int next;
        while ((next = input.read()) != -1 && next != '\n') {
            if (next != '\r') line.append((char) next);
        }
        if (next == -1) throw new java.io.EOFException("fixture closed before request headers");
        return line.toString();
    }
}
