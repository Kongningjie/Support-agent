package com.lawrence.supportagent.security;

import java.util.Objects;
import java.util.Set;

/**
 * 保存一次不可信内容评估的动作、来源和低基数信号。
 *
 * @param action 当前调用必须执行的处置动作
 * @param signals 命中的稳定信号集合，不包含原始正文
 * @param source 被检查内容的来源
 */
public record PromptSecurityAssessment(PromptSecurityAction action,
                                       Set<PromptSecuritySignal> signals,
                                       PromptSecuritySource source) {
    /** 复制信号集合并拒绝缺失的动作或来源。 */
    public PromptSecurityAssessment {
        action = Objects.requireNonNull(action, "Prompt 安全动作不能为空");
        source = Objects.requireNonNull(source, "Prompt 安全来源不能为空");
        signals = signals == null ? Set.of() : Set.copyOf(signals);
    }

    /** 返回当前内容是否必须从本次模型调用中阻断或排除。 */
    public boolean blocked() {
        return action == PromptSecurityAction.BLOCK;
    }
}
