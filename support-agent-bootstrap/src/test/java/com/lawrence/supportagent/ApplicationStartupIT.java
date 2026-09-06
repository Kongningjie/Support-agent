package com.lawrence.supportagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 验证开发环境无需 DashScope 密钥即可启动及健康分组的降级状态。 */
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(classes = SupportAgentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "support-agent.dashscope.api-key=",
                "support-agent.async-task.enabled=false",
                "support-agent.elasticsearch.url=http://127.0.0.1:1",
                "spring.data.redis.url=redis://127.0.0.1:1"
        })
class ApplicationStartupIT {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11")
            .withDatabaseName("support_agent")
            .withUsername("support_agent")
            .withPassword("test_password");

    @LocalServerPort
    private int serverPort;
    @Autowired
    private ObjectMapper objectMapper;

    /** 把 Testcontainers MySQL 连接信息注入完整应用。 */
    @DynamicPropertySource
    static void registerDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /** 验证存活探针为 UP，依赖不可用时就绪探针为 DOWN。 */
    @Test
    void shouldStartWithoutDashScopeKeyAndExposeProbeStates() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> liveness = get(client, "/actuator/health/liveness");
        HttpResponse<String> readiness = get(client, "/actuator/health/readiness");

        assertEquals(200, liveness.statusCode());
        assertTrue(liveness.body().contains("\"status\":\"UP\""));
        assertEquals(503, readiness.statusCode());
        assertTrue(readiness.body().contains("\"status\":\"DOWN\""));
    }

    /** 验证完整应用中的工单 HTTP 主路径、幂等重放和公开字段边界。 */
    @Test
    void shouldCompleteTicketHttpWorkflow() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String createKey = "startup-it-create-" + UUID.randomUUID();
        String createBody = """
                {"title":"启动失败","problemDescription":"无法连接数据库",
                "attemptedActions":"已检查端口","idempotencyKey":"%s"}
                """.formatted(createKey);
        HttpResponse<String> created = sendJson(client, "POST", "/api/v1/tickets/drafts", createBody);
        HttpResponse<String> replayed = sendJson(client, "POST", "/api/v1/tickets/drafts", createBody);
        JsonNode createdJson = objectMapper.readTree(created.body());
        JsonNode replayedJson = objectMapper.readTree(replayed.body());
        String ticketNo = createdJson.path("data").path("ticketNo").asText();

        assertEquals(201, created.statusCode());
        assertEquals(ticketNo, replayedJson.path("data").path("ticketNo").asText());
        assertTrue(ticketNo.matches("T\\d{12}"));
        assertFalse(createdJson.path("data").has("id"));

        long draftVersion = createdJson.path("data").path("version").asLong();
        HttpResponse<String> revised = sendJson(client, "PUT",
                "/api/v1/tickets/" + ticketNo + "/draft", """
                        {"title":"MySQL 启动失败","problemDescription":"连接被拒绝",
                        "attemptedActions":"已检查端口映射","version":%d}
                        """.formatted(draftVersion));
        long revisedVersion = objectMapper.readTree(revised.body()).path("data").path("version").asLong();
        assertEquals(200, revised.statusCode());

        HttpResponse<String> submitted = sendJson(client, "POST",
                "/api/v1/tickets/" + ticketNo + "/submit", """
                        {"version":%d,"idempotencyKey":"%s"}
                        """.formatted(revisedVersion, "startup-it-submit-" + UUID.randomUUID()));
        JsonNode submittedJson = objectMapper.readTree(submitted.body());
        assertEquals("OPEN", submittedJson.path("data").path("status").asText());

        HttpResponse<String> page = get(client,
                "/api/v1/tickets?status=OPEN&keyword=MySQL&page=1&size=20");
        assertEquals(1, objectMapper.readTree(page.body()).path("data")
                .path("totalElements").asLong());

        long openVersion = submittedJson.path("data").path("version").asLong();
        HttpResponse<String> closed = sendJson(client, "POST",
                "/api/v1/tickets/" + ticketNo + "/close", """
                        {"closeReason":"用户确认关闭","version":%d,"idempotencyKey":"%s"}
                        """.formatted(openVersion, "startup-it-close-" + UUID.randomUUID()));
        assertEquals("CLOSED", objectMapper.readTree(closed.body())
                .path("data").path("status").asText());

        HttpResponse<String> details = get(client, "/api/v1/tickets/" + ticketNo);
        assertEquals("CLOSED", objectMapper.readTree(details.body())
                .path("data").path("status").asText());
    }

    /** 验证 OpenAPI 已注册阶段 2 接口且未提前开放解决工单接口。 */
    @Test
    void shouldExposeOnlyCurrentStageTicketOperations() throws Exception {
        JsonNode paths = objectMapper.readTree(get(HttpClient.newHttpClient(), "/v3/api-docs").body())
                .path("paths");

        assertTrue(paths.has("/api/v1/tickets/drafts"));
        assertTrue(paths.has("/api/v1/async-tasks/{taskId}/retry"));
        assertFalse(paths.has("/api/v1/tickets/{ticketNo}/resolve"));
    }

    /** 向本地随机端口发送健康检查请求。 */
    private HttpResponse<String> get(HttpClient client, String path)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + serverPort + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** 向本地随机端口发送 JSON 写请求并返回原始响应。 */
    private HttpResponse<String> sendJson(HttpClient client, String method, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + serverPort + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
