package com.lawrence.supportagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.lawrence.supportagent.sharedkernel.port.OperatorProvider;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.sharedkernel.port.UuidGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 验证基础配置绑定、抽象实现和生产环境密钥门禁。 */
class FoundationConfigurationTest {
    private final ApplicationContextRunner contextRunner = contextRunner("test-operator", "test-key");

    /** 验证配置字段及统一时间、UUID、操作者实现均可用。 */
    @Test
    void shouldBindPropertiesAndProvideFoundationPorts() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            SupportAgentProperties properties = context.getBean(SupportAgentProperties.class);
            assertThat(properties.operatorId()).isEqualTo("test-operator");
            assertThat(properties.dashscope().apiKey()).isEqualTo("test-key");
            assertThat(properties.elasticsearch().url()).isEqualTo("http://localhost:19200");
            assertThat(context.getBean(OperatorProvider.class).currentOperator().value())
                    .isEqualTo("test-operator");
            assertThat(context.getBean(TimeProvider.class).now()).isNotNull();
            assertThat(context.getBean(UuidGenerator.class).generate()).isNotNull();
        });
    }

    /** 验证开发环境缺少 DashScope 密钥不会阻止基础配置启动。 */
    @Test
    void shouldAllowMissingDashScopeKeyInDevelopment() {
        contextRunner("dev-operator", "")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** 验证生产环境缺少 DashScope 密钥时上下文启动失败。 */
    @Test
    void shouldRejectMissingDashScopeKeyInProduction() {
        contextRunner("prod-operator", "")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasRootCauseMessage("生产环境必须配置 DASHSCOPE_API_KEY"));
    }

    /** 创建不依赖 application.yml 的完整配置绑定测试上下文。 */
    private static ApplicationContextRunner contextRunner(String operatorId, String apiKey) {
        return new ApplicationContextRunner()
                .withUserConfiguration(ConfigurationPropertiesAutoConfiguration.class,
                        FoundationConfiguration.class)
                .withPropertyValues(
                        "support-agent.operator-id=" + operatorId,
                        "support-agent.dashscope.api-key=" + apiKey,
                        "support-agent.dashscope.base-url=https://dashscope.aliyuncs.com/api/v1",
                        "support-agent.dashscope.chat-model=qwen3.8-flash",
                        "support-agent.dashscope.intent-model=qwen3.7-flash",
                        "support-agent.dashscope.summary-model=qwen3.7-flash",
                        "support-agent.dashscope.memory-model=qwen3.7-flash",
                        "support-agent.dashscope.embedding-model=text-embedding-v4",
                        "support-agent.dashscope.rerank-model=qwen3-rerank",
                        "support-agent.dashscope.chat-timeout=120s",
                        "support-agent.dashscope.intent-timeout=3s",
                        "support-agent.dashscope.summary-timeout=30s",
                        "support-agent.dashscope.memory-timeout=15s",
                        "support-agent.dashscope.ticket-timeout=30s",
                        "support-agent.dashscope.resolved-case-timeout=60s",
                        "support-agent.dashscope.embedding-timeout=10s",
                        "support-agent.dashscope.rerank-timeout=10s",
                        "support-agent.dashscope.retry-max-attempts=3",
                        "support-agent.dashscope.retry-initial-delay=100ms",
                        "support-agent.dashscope.chat-max-output-tokens=1200",
                        "support-agent.dashscope.intent-max-output-tokens=256",
                        "support-agent.dashscope.summary-max-output-tokens=1200",
                        "support-agent.dashscope.memory-max-output-tokens=400",
                        "support-agent.dashscope.structured-max-output-tokens=800",
                        "support-agent.dashscope.grounded-prompt-variant=ORIGINAL",
                        "support-agent.elasticsearch.url=http://localhost:19200",
                        "support-agent.elasticsearch.username=test-user",
                        "support-agent.elasticsearch.password=test-password",
                        "support-agent.elasticsearch.knowledge-index=support_knowledge_v1",
                        "support-agent.elasticsearch.knowledge-alias=support_knowledge_current",
                        "support-agent.elasticsearch.connect-timeout=2s",
                        "support-agent.elasticsearch.connection-request-timeout=2s",
                        "support-agent.elasticsearch.response-timeout=10s");
    }
}
