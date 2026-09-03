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
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ConfigurationPropertiesAutoConfiguration.class,
                    FoundationConfiguration.class)
            .withPropertyValues(
                    "support-agent.operator-id=test-operator",
                    "support-agent.dashscope.api-key=test-key",
                    "support-agent.elasticsearch.url=http://localhost:19200",
                    "support-agent.elasticsearch.username=test-user",
                    "support-agent.elasticsearch.password=test-password");

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
        new ApplicationContextRunner()
                .withUserConfiguration(ConfigurationPropertiesAutoConfiguration.class,
                        FoundationConfiguration.class)
                .withPropertyValues("support-agent.operator-id=dev-operator")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** 验证生产环境缺少 DashScope 密钥时上下文启动失败。 */
    @Test
    void shouldRejectMissingDashScopeKeyInProduction() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withUserConfiguration(ConfigurationPropertiesAutoConfiguration.class,
                        FoundationConfiguration.class)
                .withPropertyValues("support-agent.operator-id=prod-operator")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasRootCauseMessage("生产环境必须配置 DASHSCOPE_API_KEY"));
    }
}
