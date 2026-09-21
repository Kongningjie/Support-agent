package com.lawrence.supportagent.security;

/**
 * 保存阶段 15～16 冻结的 LLM 输入与输出安全开关。
 *
 * @param enabled 是否启用输入安全策略
 * @param blockHighConfidenceInput 是否阻断高置信度当前用户注入
 * @param excludeHighRiskContext 是否排除高风险历史、摘要、记忆、证据和工单字段
 * @param promptCanaryEnabled 是否为受保护生成调用创建随机泄漏标记
 * @param maximumRegenerations 可修复输出失败后的最大完整重生成次数
 */
public record LlmSecuritySettings(boolean enabled, boolean blockHighConfidenceInput,
                                  boolean excludeHighRiskContext, boolean promptCanaryEnabled,
                                  int maximumRegenerations) {
    /** 保持阶段 15 调用方兼容，并采用阶段 16 冻结默认值。 */
    public LlmSecuritySettings(boolean enabled, boolean blockHighConfidenceInput,
                               boolean excludeHighRiskContext) {
        this(enabled, blockHighConfidenceInput, excludeHighRiskContext, true, 1);
    }

    /** 拒绝超出冻结范围的重生成次数。 */
    public LlmSecuritySettings {
        if (maximumRegenerations < 0 || maximumRegenerations > 1) {
            throw new IllegalArgumentException("模型输出最大重生成次数只允许 0 或 1");
        }
    }
}
