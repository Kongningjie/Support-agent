package com.lawrence.supportagent.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lawrence.supportagent.agent.model.GroundedPromptVariant;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 验证外部依赖配置在应用启动绑定阶段即可发现错误。 */
class SupportAgentPropertiesTest {
    /** 验证默认范围内的模型与 Elasticsearch 配置可正常构造。 */
    @Test
    void shouldAcceptValidOperationalConfiguration() {
        assertDoesNotThrow(() -> properties("https://dashscope.aliyuncs.com/api/v1",
                Duration.ofSeconds(10), 3));
    }

    /** 验证错误协议和空主机不会延迟到首次外部调用才失败。 */
    @Test
    void shouldRejectInvalidExternalUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> properties("file:///tmp/dashscope", Duration.ofSeconds(10), 3));
    }

    /** 验证非正超时和超过三次的放大重试会阻止应用启动。 */
    @Test
    void shouldRejectUnsafeTimeoutAndRetrySettings() {
        assertThrows(IllegalArgumentException.class,
                () -> properties("https://dashscope.aliyuncs.com/api/v1", Duration.ZERO, 3));
        assertThrows(IllegalArgumentException.class,
                () -> properties("https://dashscope.aliyuncs.com/api/v1",
                        Duration.ofSeconds(10), 4));
    }

    /** 构造覆盖阶段 9 新增配置字段的完整测试属性。 */
    private SupportAgentProperties properties(String baseUrl, Duration embeddingTimeout,
                                               int retryAttempts) {
        SupportAgentProperties.DashScope dashScope = new SupportAgentProperties.DashScope(
                "", baseUrl, "qwen3.8-flash", "qwen3.7-flash", "qwen3.7-flash", "qwen3.7-flash", "text-embedding-v4",
                "qwen3-rerank", Duration.ofSeconds(120), Duration.ofSeconds(3),
                Duration.ofSeconds(30), Duration.ofSeconds(15),
                Duration.ofSeconds(30), Duration.ofSeconds(60), embeddingTimeout,
                Duration.ofSeconds(10), retryAttempts, Duration.ofMillis(100),
                1200, 256, 1200, 400, 800, GroundedPromptVariant.ORIGINAL);
        SupportAgentProperties.Elasticsearch elasticsearch =
                new SupportAgentProperties.Elasticsearch("http://localhost:9200", "", "",
                        "support_knowledge_v1", "support_knowledge_current",
                        Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(10));
        return new SupportAgentProperties("dev-operator", dashScope, elasticsearch);
    }
}
