package com.lawrence.supportagent.evaluation;

import java.util.List;

/** 保存一条不含真实敏感信息的固定中文安全评测样本。 */
public record SecurityEvaluationCase(String caseId, SecurityEvaluationCategory category,
                                     SecurityEvaluationSource source, String input,
                                     SecurityEvaluationAction expectedAction,
                                     List<String> requiredSignals,
                                     List<String> forbiddenSignals, String notes) {
    /** 创建不可变样本并拒绝缺失字段。 */
    public SecurityEvaluationCase {
        if (caseId == null || caseId.isBlank() || category == null || source == null
                || input == null || input.isBlank() || expectedAction == null
                || requiredSignals == null || forbiddenSignals == null
                || notes == null || notes.isBlank()) {
            throw new IllegalArgumentException("安全评测样本字段不能为空");
        }
        requiredSignals = List.copyOf(requiredSignals);
        forbiddenSignals = List.copyOf(forbiddenSignals);
    }
}
