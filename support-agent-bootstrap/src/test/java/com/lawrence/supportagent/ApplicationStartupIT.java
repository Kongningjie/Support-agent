package com.lawrence.supportagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** 验证开发环境无需 DashScope 密钥即可启动及健康分组的降级状态。 */
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(classes = SupportAgentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "support-agent.dashscope.api-key=",
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

    /** 向本地随机端口发送健康检查请求。 */
    private HttpResponse<String> get(HttpClient client, String path)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + serverPort + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
