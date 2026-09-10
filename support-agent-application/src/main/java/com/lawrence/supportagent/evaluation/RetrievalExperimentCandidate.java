package com.lawrence.supportagent.evaluation;

import java.util.Map;

/**
 * 保存参数矩阵中的一个候选，只记录相对基线发生变化的字段。
 *
 * @param candidateId 报告内稳定且唯一的候选编号
 * @param group 本候选唯一允许改变的变量组
 * @param description 该变量变化的简体中文目的说明
 * @param parameterOverrides 相对基线覆盖的非空参数键值
 */
public record RetrievalExperimentCandidate(String candidateId, RetrievalExperimentGroup group,
                                           String description,
                                           Map<String, String> parameterOverrides) {
    /** 校验候选身份、说明和覆盖参数均可用于可复现实验。 */
    public RetrievalExperimentCandidate {
        if (candidateId == null || !candidateId.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("实验候选编号不合法");
        }
        if (group == null || description == null || description.isBlank()
                || parameterOverrides == null || parameterOverrides.isEmpty()
                || parameterOverrides.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || entry.getKey().isBlank()
                        || entry.getValue() == null || entry.getValue().isBlank())) {
            throw new IllegalArgumentException("实验候选字段不能为空");
        }
        parameterOverrides = Map.copyOf(parameterOverrides);
    }
}
