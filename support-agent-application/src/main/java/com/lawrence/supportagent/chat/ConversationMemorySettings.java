package com.lawrence.supportagent.chat;

/**
 * 保存阶段 10 冻结的会话记忆预算与滚动摘要阈值。
 *
 * @param inputBudgetTokens 单次 Chat 输入内部工程预算
 * @param outputReserveTokens 为回答输出预留的 Token
 * @param safetyMarginTokens 应对估算误差的固定安全余量
 * @param recentFullTurns Redis 与模型上下文优先保留的最近完整轮次
 * @param softTriggerTurns 异步摘要的成功轮次软阈值
 * @param softTriggerMemoryTokens 异步摘要的会话记忆 Token 软阈值
 * @param hardTriggerTurns 同步摘要的原始轮次硬阈值
 */
public record ConversationMemorySettings(int inputBudgetTokens, int outputReserveTokens,
                                         int safetyMarginTokens, int recentFullTurns,
                                         int softTriggerTurns, int softTriggerMemoryTokens,
                                         int hardTriggerTurns) {
    /** 校验预算均为正值且轮次阈值满足冻结的严格顺序。 */
    public ConversationMemorySettings {
        if (inputBudgetTokens <= 0 || outputReserveTokens <= 0 || safetyMarginTokens <= 0
                || recentFullTurns <= 0 || softTriggerTurns <= 0
                || softTriggerMemoryTokens <= 0 || hardTriggerTurns <= 0
                || recentFullTurns >= softTriggerTurns || softTriggerTurns > hardTriggerTurns
                || outputReserveTokens + safetyMarginTokens >= inputBudgetTokens) {
            throw new IllegalArgumentException("会话记忆预算或滚动摘要阈值不合法");
        }
    }

    /** 返回扣除回答预留和安全余量后的最大输入正文预算。 */
    public int availableInputTokens() {
        return inputBudgetTokens - outputReserveTokens - safetyMarginTokens;
    }
}
