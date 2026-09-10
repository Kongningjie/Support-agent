package com.lawrence.supportagent.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 验证阶段 8 模型生成参数的默认值和边界。 */
class ModelGenerationSettingsTest {
    /** 验证冻结默认值分别覆盖普通、意图和结构化生成。 */
    @Test
    void shouldExposeStageEightDefaults() {
        ModelGenerationSettings settings = ModelGenerationSettings.stageEightDefaults();

        assertThat(settings.chatTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(settings.intentTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(settings.ticketTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(settings.resolvedCaseTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(settings.chatMaxOutputTokens()).isEqualTo(1200);
        assertThat(settings.intentMaxOutputTokens()).isEqualTo(256);
        assertThat(settings.structuredMaxOutputTokens()).isEqualTo(800);
    }

    /** 验证非正超时和输出上限在启动前被拒绝。 */
    @Test
    void shouldRejectNonPositiveValues() {
        assertThatThrownBy(() -> new ModelGenerationSettings(Duration.ZERO,
                Duration.ofSeconds(3), Duration.ofSeconds(30), Duration.ofSeconds(60),
                1200, 256, 800)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelGenerationSettings(Duration.ofSeconds(120),
                Duration.ofSeconds(3), Duration.ofSeconds(30), Duration.ofSeconds(60),
                0, 256, 800)).isInstanceOf(IllegalArgumentException.class);
    }
}
