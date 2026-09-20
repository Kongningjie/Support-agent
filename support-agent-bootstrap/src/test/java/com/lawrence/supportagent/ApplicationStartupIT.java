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
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 验证开发环境无需 DashScope 密钥即可启动及健康分组的降级状态。 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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
    private static final String ADMIN_USERNAME = "stage-admin";
    private static final String ADMIN_PASSWORD = "stage-admin-password";
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
    private volatile String adminToken;

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
        registry.add("support-agent.auth.bootstrap-admin.enabled", () -> "true");
        registry.add("support-agent.auth.bootstrap-admin.username", () -> ADMIN_USERNAME);
        registry.add("support-agent.auth.bootstrap-admin.display-name", () -> "Stage Admin");
        registry.add("support-agent.auth.bootstrap-admin.password", () -> ADMIN_PASSWORD);
    }

    /** 验证无需模型的越界分支可按稳定顺序完成真实 SSE 响应并写入审计。 */
    @Test
    void shouldStreamFixedOutOfScopeAnswer() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + serverPort + "/api/v1/chat/stream"))
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken())
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

    /** 验证认证会话可查询、重置和删除，且重置后旧轮次不会继续暴露。 */
    @Test
    void shouldManageAuthenticatedConversationLifecycle() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> streamed = send(client, "POST", "/api/v1/chat/stream", """
                {"clientMessageId":"%s","message":"告诉我今天的股票行情"}
                """.formatted(UUID.randomUUID()), accessToken());
        var matcher = java.util.regex.Pattern.compile("\\\"conversationId\\\":\\\"([0-9a-f-]{36})\\\"")
                .matcher(streamed.body());
        assertTrue(matcher.find());
        String conversationId = matcher.group(1);

        JsonNode page = objectMapper.readTree(get(client, "/api/v1/conversations?page=1&size=20").body())
                .path("data");
        assertTrue(page.path("items").toString().contains(conversationId));
        JsonNode details = objectMapper.readTree(get(client, "/api/v1/conversations/" + conversationId).body())
                .path("data");
        assertEquals(1, details.path("conversation").path("version").asLong());
        assertEquals(1, details.path("recentTurns").size());
        assertFalse(details.toString().contains("agentState"));

        JsonNode reset = objectMapper.readTree(sendJson(client, "POST",
                "/api/v1/conversations/" + conversationId + "/reset", "{\"expectedVersion\":1}").body())
                .path("data");
        assertEquals(0, reset.path("version").asLong());
        assertEquals(1, reset.path("generation").asLong());
        assertEquals(0, objectMapper.readTree(get(client, "/api/v1/conversations/" + conversationId).body())
                .path("data").path("recentTurns").size());

        HttpResponse<String> deleted = send(client, "DELETE",
                "/api/v1/conversations/" + conversationId + "?expectedVersion=0", "", accessToken());
        assertEquals(200, deleted.statusCode());
        assertEquals(404, get(client, "/api/v1/conversations/" + conversationId).statusCode());
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
        String ticketNo = createdJson.path("data").path("ticketNo").stringValue();

        assertEquals(201, created.statusCode());
        assertEquals(ticketNo, replayedJson.path("data").path("ticketNo").stringValue());
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
        assertEquals("OPEN", submittedJson.path("data").path("status").stringValue());

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
                .path("data").path("status").stringValue());

        HttpResponse<String> details = get(client, "/api/v1/tickets/" + ticketNo);
        assertEquals("CLOSED", objectMapper.readTree(details.body())
                .path("data").path("status").stringValue());
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
        String documentId = createdJson.path("documentId").stringValue();

        assertEquals(201, created.statusCode());
        assertEquals(documentId, objectMapper.readTree(replayed.body())
                .path("data").path("documentId").stringValue());
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

    /** 验证匿名 401、管理员创建、普通用户 403、禁用撤销和注销完整认证链路。 */
    @Test
    void shouldEnforceAuthenticationAndTwoLevelRoles() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> anonymous = send(client, "GET", "/api/v1/users/me", "", null);
        assertEquals(401, anonymous.statusCode());
        assertTrue(anonymous.body().contains("AUTH_UNAUTHORIZED"));

        String username = "stage-user-" + UUID.randomUUID().toString().substring(0, 8);
        String createBody = """
                {"username":"%s","displayName":"阶段用户","password":"stage-user-password","role":"USER",
                 "idempotencyKey":"create-%s"}
                """.formatted(username, username);
        HttpResponse<String> created = send(client, "POST", "/api/v1/admin/users",
                createBody, accessToken());
        assertEquals(201, created.statusCode());
        JsonNode createdUser = objectMapper.readTree(created.body()).path("data");
        assertFalse(createdUser.has("password"));
        HttpResponse<String> replayed = send(client, "POST", "/api/v1/admin/users",
                createBody, accessToken());
        assertEquals(createdUser.path("userId"),
                objectMapper.readTree(replayed.body()).path("data").path("userId"));

        String userToken = login(username, "stage-user-password");
        assertEquals(200, send(client, "GET", "/api/v1/users/me", "", userToken).statusCode());
        assertEquals(403, send(client, "POST", "/api/v1/admin/users", """
                {"username":"forbidden-user","displayName":"越权","password":"forbidden-password","role":"USER",
                 "idempotencyKey":"forbidden-create"}
                """, userToken).statusCode());

        String userId = createdUser.path("userId").stringValue();
        long version = createdUser.path("version").asLong();
        HttpResponse<String> disabled = send(client, "PATCH",
                "/api/v1/admin/users/" + userId + "/status",
                "{\"status\":\"DISABLED\",\"version\":" + version + "}", accessToken());
        assertEquals(200, disabled.statusCode());
        assertEquals(401, send(client, "GET", "/api/v1/users/me", "", userToken).statusCode());

        String logoutToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        assertEquals(200, send(client, "POST", "/api/v1/auth/logout", "{}", logoutToken).statusCode());
        assertEquals(401, send(client, "GET", "/api/v1/users/me", "", logoutToken).statusCode());
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
        assertTrue(paths.has("/api/v1/conversations"));
        assertTrue(paths.has("/api/v1/conversations/{conversationId}"));
        assertTrue(paths.has("/api/v1/conversations/{conversationId}/reset"));
        assertTrue(paths.has("/api/v1/tickets/drafts/from-conversation"));
        assertTrue(paths.has("/api/v1/tickets/{ticketNo}/resolve"));
        assertTrue(paths.has("/api/v1/resolved-cases/{caseId}"));
        assertTrue(paths.has("/api/v1/resolved-cases/{caseId}/publish"));
        assertTrue(paths.has("/api/v1/retrieval-evaluations"));
    }

    /** 向本地随机端口发送健康检查请求。 */
    private HttpResponse<String> get(HttpClient client, String path)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + serverPort + path));
        if (!path.startsWith("/actuator/") && !path.startsWith("/v3/api-docs")) {
            builder.header("Authorization", "Bearer " + accessToken());
        }
        HttpRequest request = builder.GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** 向本地随机端口发送 JSON 写请求并返回原始响应。 */
    private HttpResponse<String> sendJson(HttpClient client, String method, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + serverPort + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken())
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** 返回缓存的管理员 Token，首次使用时通过公开登录接口获取。 */
    private String accessToken() throws IOException, InterruptedException {
        if (adminToken == null) {
            adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        }
        return adminToken;
    }

    /** 使用用户名密码登录并返回一次性原始 Bearer Token。 */
    private String login(String username, String password) throws IOException, InterruptedException {
        HttpResponse<String> response = send(HttpClient.newHttpClient(), "POST", "/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}", null);
        assertEquals(200, response.statusCode());
        return objectMapper.readTree(response.body()).path("data").path("accessToken").stringValue();
    }

    /** 使用可选 Bearer Token 发送原始 HTTP 请求，用于认证边界测试。 */
    private HttpResponse<String> send(HttpClient client, String method, String path, String body,
                                      String token) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + serverPort + path))
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpRequest request = builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build();
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
