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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
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
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4.7")).withExposedPorts(6379);

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
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost()
                + ":" + REDIS.getMappedPort(6379));
    }

    /** 验证无需模型的越界分支可按稳定顺序完成真实 SSE 响应并写入审计。 */
    @Test
    void shouldStreamFixedOutOfScopeAnswer() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + serverPort + "/api/v1/chat/stream"))
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"clientMessageId":"%s","message":"告诉我今天的股票行情"}
                        """.formatted(UUID.randomUUID())))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("")
                .startsWith("text/event-stream"));
        assertEventOrder(response.body(), "conversation.started", "answer.started",
                "answer.delta", "answer.completed");
        assertTrue(response.body().contains("\"resultStatus\":\"OUT_OF_SCOPE\""));
        assertTrue(response.body().contains("\"conversationVersion\":1"));
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

    /** 验证直接文本知识草稿的创建、幂等、分页、修改及软删除 HTTP 主路径。 */
    @Test
    void shouldCompleteManagedDocumentDraftHttpWorkflow() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String key = "startup-it-knowledge-" + UUID.randomUUID();
        String content = "唯一正文-" + UUID.randomUUID();
        String createBody = """
                {"title":"MySQL 排障","content":"%s","idempotencyKey":"%s"}
                """.formatted(content, key);
        HttpResponse<String> created = sendJson(client, "POST",
                "/api/v1/knowledge/documents/text", createBody);
        HttpResponse<String> replayed = sendJson(client, "POST",
                "/api/v1/knowledge/documents/text", createBody);
        JsonNode createdJson = objectMapper.readTree(created.body()).path("data");
        String documentId = createdJson.path("documentId").asText();

        assertEquals(201, created.statusCode());
        assertEquals(documentId, objectMapper.readTree(replayed.body())
                .path("data").path("documentId").asText());
        assertTrue(documentId.matches("\\d+"));

        HttpResponse<String> page = get(client,
                "/api/v1/knowledge/documents?status=DRAFT&keyword=MySQL&page=1&size=20");
        assertTrue(objectMapper.readTree(page.body()).path("data")
                .path("totalElements").asLong() >= 1);
        long version = createdJson.path("version").asLong();
        HttpResponse<String> revised = sendJson(client, "PUT",
                "/api/v1/knowledge/documents/" + documentId + "/draft", """
                        {"title":"MySQL 连接排障","content":"%s-修改","version":%d}
                        """.formatted(content, version));
        long revisedVersion = objectMapper.readTree(revised.body())
                .path("data").path("version").asLong();
        assertEquals(version + 1, revisedVersion);

        HttpResponse<String> deleted = sendJson(client, "DELETE",
                "/api/v1/knowledge/documents/" + documentId + "?version=" + revisedVersion, "");
        assertEquals(200, deleted.statusCode());
        assertEquals(404, get(client, "/api/v1/knowledge/documents/" + documentId).statusCode());
    }

    /** 验证 OpenAPI 已注册阶段五允许的工单、案例和评测接口。 */
    @Test
    void shouldExposeOnlyCurrentStageTicketOperations() throws Exception {
        JsonNode paths = objectMapper.readTree(get(HttpClient.newHttpClient(), "/v3/api-docs").body())
                .path("paths");

        assertTrue(paths.has("/api/v1/tickets/drafts"));
        assertTrue(paths.has("/api/v1/async-tasks/{taskId}/retry"));
        assertTrue(paths.has("/api/v1/knowledge/documents/text"));
        assertTrue(paths.has("/api/v1/knowledge/documents/{documentId}/publish"));
        assertTrue(paths.has("/api/v1/chat/stream"));
        assertTrue(paths.has("/api/v1/tickets/drafts/from-conversation"));
        assertTrue(paths.has("/api/v1/tickets/{ticketNo}/resolve"));
        assertTrue(paths.has("/api/v1/resolved-cases/{caseId}"));
        assertTrue(paths.has("/api/v1/resolved-cases/{caseId}/publish"));
        assertTrue(paths.has("/api/v1/retrieval-evaluations"));
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

    /** 断言多个 SSE 事件名称按给定先后顺序出现。 */
    private void assertEventOrder(String body, String... eventNames) {
        int previous = -1;
        for (String eventName : eventNames) {
            int current = body.indexOf("event:" + eventName, previous + 1);
            assertTrue(current > previous, "SSE 事件顺序错误：" + eventName);
            previous = current;
        }
    }
}
