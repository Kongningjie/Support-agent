package com.lawrence.supportagent.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

/** 验证 DashScope 配置和 Elasticsearch 依赖的健康状态。 */
class DependencyHealthIndicatorTest {
    /** 验证 DashScope 密钥只影响配置健康状态且不会暴露密钥。 */
    @Test
    void shouldReportDashScopeConfigurationState() {
        DashScopeConfigurationHealthIndicator missing = new DashScopeConfigurationHealthIndicator(
                properties("", "http://localhost:9200"));
        DashScopeConfigurationHealthIndicator configured = new DashScopeConfigurationHealthIndicator(
                properties("test-key", "http://localhost:9200"));

        assertEquals(Status.UNKNOWN, missing.health().getStatus());
        assertEquals(Status.UP, configured.health().getStatus());
    }

    /** 验证 Elasticsearch 可达时为 UP、不可达时为 DOWN。 */
    @Test
    void shouldReportElasticsearchAvailability() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            ElasticsearchHealthIndicator available = new ElasticsearchHealthIndicator(
                    properties("", "http://127.0.0.1:" + port));
            assertEquals(Status.UP, available.health().getStatus());
        } finally {
            server.stop(0);
        }

        ElasticsearchHealthIndicator unavailable = new ElasticsearchHealthIndicator(
                properties("", "http://127.0.0.1:" + port));
        assertEquals(Status.DOWN, unavailable.health().getStatus());
    }

    /** 验证配置基础认证后，健康探测会发送对应 Authorization 请求头。 */
    @Test
    void shouldUseElasticsearchBasicAuthenticationWhenConfigured() throws IOException {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            SupportAgentProperties configured = new SupportAgentProperties("test-operator",
                    dashScope(""),
                    new SupportAgentProperties.Elasticsearch(
                            "http://127.0.0.1:" + server.getAddress().getPort(), "elastic", "secret",
                            "support_knowledge_v1", "support_knowledge_current"));

            assertEquals(Status.UP, new ElasticsearchHealthIndicator(configured).health().getStatus());
            assertEquals("Basic ZWxhc3RpYzpzZWNyZXQ=", authorization.get());
        } finally {
            server.stop(0);
        }
    }

    /** 构造健康探测测试所需的最小配置。 */
    private SupportAgentProperties properties(String apiKey, String elasticsearchUrl) {
        return new SupportAgentProperties("test-operator",
                dashScope(apiKey),
                new SupportAgentProperties.Elasticsearch(elasticsearchUrl, "", "",
                        "support_knowledge_v1", "support_knowledge_current"));
    }

    /** 创建包含阶段 8 默认生成参数的测试 DashScope 配置。 */
    private SupportAgentProperties.DashScope dashScope(String apiKey) {
        return new SupportAgentProperties.DashScope(apiKey,
                "https://dashscope.aliyuncs.com/api/v1", "qwen3.8-flash",
                "qwen3.7-flash", "text-embedding-v4", "qwen3-rerank",
                java.time.Duration.ofSeconds(120), java.time.Duration.ofSeconds(3),
                java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(60), 1200, 256, 800,
                com.lawrence.supportagent.agent.model.GroundedPromptVariant.ORIGINAL);
    }
}
