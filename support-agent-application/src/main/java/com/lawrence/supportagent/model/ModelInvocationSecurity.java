package com.lawrence.supportagent.model;

import java.util.List;

/** 保存单次生成调用的短期泄漏标记和上一轮低基数校验反馈。 */
public record ModelInvocationSecurity(String canary, List<String> feedbackRules) {
    /** 复制反馈规则并拒绝包含正文或分隔符的非枚举式值。 */
    public ModelInvocationSecurity {
        feedbackRules = feedbackRules == null ? List.of() : feedbackRules.stream()
                .map(String::trim)
                .filter(value -> value.matches("[A-Z][A-Z0-9_]{0,63}"))
                .distinct().toList();
    }

    /** 创建不携带泄漏标记或校验反馈的兼容调用上下文。 */
    public static ModelInvocationSecurity none() {
        return new ModelInvocationSecurity(null, List.of());
    }

    /** 把安全规则编号转换为可交给模型的稳定反馈，不包含失败正文。 */
    public String feedback() {
        return String.join(",", feedbackRules);
    }
}
