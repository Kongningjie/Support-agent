package com.lawrence.supportagent.security;

import java.util.List;
import java.util.Set;

/** 记录不含正文和业务标识的 LLM 安全低基数指标。 */
public interface LlmSecurityTelemetryPort {
    /** 记录一次输入或上下文安全评估。 */
    void recordPromptAssessment(PromptSecuritySource source, PromptSecurityAction action,
                                Set<PromptSecuritySignal> signals);

    /** 记录一次模型完整输出安全评估。 */
    void recordOutputAssessment(ModelOutputType type, ModelOutputAction action,
                                List<String> rules);

    /** 记录某模型分支因可修复失败而执行完整重生成。 */
    void recordRegeneration(ModelOutputType type);

    /** 记录某模型分支最终拒绝发送或持久化输出。 */
    void recordFinalRejection(ModelOutputType type);

    /** 记录一次高风险非用户上下文排除。 */
    void recordContextExclusion(PromptSecuritySource source);

    /** 返回不产生副作用的默认实现，供隔离测试和兼容构造器使用。 */
    static LlmSecurityTelemetryPort noOp() {
        return new LlmSecurityTelemetryPort() {
            /** {@inheritDoc} */
            @Override public void recordPromptAssessment(PromptSecuritySource source,
                    PromptSecurityAction action, Set<PromptSecuritySignal> signals) { }
            /** {@inheritDoc} */
            @Override public void recordOutputAssessment(ModelOutputType type,
                    ModelOutputAction action, List<String> rules) { }
            /** {@inheritDoc} */
            @Override public void recordRegeneration(ModelOutputType type) { }
            /** {@inheritDoc} */
            @Override public void recordFinalRejection(ModelOutputType type) { }
            /** {@inheritDoc} */
            @Override public void recordContextExclusion(PromptSecuritySource source) { }
        };
    }
}
