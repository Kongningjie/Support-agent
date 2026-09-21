package com.lawrence.supportagent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lawrence.supportagent.security.LlmSecuritySettings;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 验证阶段 15 LLM 安全配置默认值和生产环境强制约束。 */
class LlmSecurityConfigurationTest {
    private final ChatConfiguration configuration = new ChatConfiguration();

    /** 非生产测试环境允许显式关闭策略以执行隔离测试。 */
    @Test
    void shouldAllowExplicitOverrideOutsideProduction() {
        LlmSecuritySettings settings = configuration.llmSecuritySettings(
                false, false, false, false, 0,
                new MockEnvironment().withProperty("spring.profiles.active", "test"));

        assertThat(settings.enabled()).isFalse();
        assertThat(settings.blockHighConfidenceInput()).isFalse();
        assertThat(settings.excludeHighRiskContext()).isFalse();
        assertThat(settings.promptCanaryEnabled()).isFalse();
        assertThat(settings.maximumRegenerations()).isZero();
    }

    /** 生产环境关闭任一冻结安全开关时必须在启动装配阶段失败。 */
    @Test
    void shouldRejectDisabledSecuritySwitchInProduction() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> configuration.llmSecuritySettings(
                true, false, true, true, 1, environment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("生产环境不得关闭 LLM 安全策略或修改输出重生成次数");
    }

    /** 生产环境必须启用随机标记并保持一次完整重生成上限。 */
    @Test
    void shouldRejectOutputSecurityOverrideInProduction() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> configuration.llmSecuritySettings(
                true, true, true, false, 1, environment))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> configuration.llmSecuritySettings(
                true, true, true, true, 0, environment))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
