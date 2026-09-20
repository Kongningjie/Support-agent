package com.lawrence.supportagent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 验证会话记忆预算和三段轮次阈值的启动前约束。 */
class ConversationMemorySettingsTest {
    /** 合法冻结参数应计算出扣除输出和安全余量后的输入正文预算。 */
    @Test
    void shouldExposeAvailableInputBudget() {
        ConversationMemorySettings settings = new ConversationMemorySettings(
                24_000, 1_200, 1_024, 6, 12, 6_000, 20);
        assertThat(settings.availableInputTokens()).isEqualTo(21_776);
    }

    /** 最近轮次、软阈值和硬阈值顺序不合法时必须拒绝启动。 */
    @Test
    void shouldRejectInvalidTurnThresholdOrder() {
        assertThatThrownBy(() -> new ConversationMemorySettings(
                24_000, 1_200, 1_024, 12, 12, 6_000, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
