package com.lawrence.supportagent.agent.model;

import java.time.Duration;

/**
 * 保存 Chat、意图和结构化生成的显式超时及输出上限。
 *
 * @param chatTimeout 普通 Chat 完整生成超时
 * @param intentTimeout 意图识别总超时
 * @param ticketTimeout 工单结构化生成超时
 * @param resolvedCaseTimeout 案例结构化生成超时
 * @param chatMaxOutputTokens 普通 Chat 最大输出 Token
 * @param intentMaxOutputTokens 意图识别最大输出 Token
 * @param structuredMaxOutputTokens 工单和案例结构化生成最大输出 Token
 */
public record ModelGenerationSettings(
        Duration chatTimeout,
        Duration intentTimeout,
        Duration ticketTimeout,
        Duration resolvedCaseTimeout,
        int chatMaxOutputTokens,
        int intentMaxOutputTokens,
        int structuredMaxOutputTokens) {

    /** 校验所有时长和输出上限均为正数。 */
    public ModelGenerationSettings {
        requirePositive(chatTimeout, "Chat 超时");
        requirePositive(intentTimeout, "意图识别超时");
        requirePositive(ticketTimeout, "工单生成超时");
        requirePositive(resolvedCaseTimeout, "案例生成超时");
        if (chatMaxOutputTokens <= 0 || intentMaxOutputTokens <= 0
                || structuredMaxOutputTokens <= 0) {
            throw new IllegalArgumentException("模型最大输出 Token 必须大于 0");
        }
    }

    /** 返回阶段 8 冻结的兼容默认值。 */
    public static ModelGenerationSettings stageEightDefaults() {
        return new ModelGenerationSettings(Duration.ofSeconds(120), Duration.ofSeconds(3),
                Duration.ofSeconds(30), Duration.ofSeconds(60), 1200, 256, 800);
    }

    /** 校验指定时长不为空且大于零。 */
    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + "必须大于 0");
        }
    }
}
