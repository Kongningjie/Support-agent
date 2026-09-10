package com.lawrence.supportagent.evaluation;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 保存阶段七分组实验矩阵，并阻止单个候选跨越多个变量组。
 *
 * @param schemaVersion 参数矩阵结构版本
 * @param baselineParameters 当前生产基线的完整参数快照
 * @param candidates 按执行顺序保存的实验候选
 */
public record RetrievalExperimentMatrix(String schemaVersion,
                                        Map<String, String> baselineParameters,
                                        List<RetrievalExperimentCandidate> candidates) {
    private static final Map<RetrievalExperimentGroup, Set<String>> ALLOWED_KEYS = allowedKeys();

    /** 校验基线、候选编号及每个候选的变量组边界。 */
    public RetrievalExperimentMatrix {
        if (schemaVersion == null || schemaVersion.isBlank()
                || baselineParameters == null || baselineParameters.isEmpty()
                || candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("实验矩阵字段不能为空");
        }
        Set<String> ids = new HashSet<>();
        for (RetrievalExperimentCandidate candidate : candidates) {
            if (candidate == null || !ids.add(candidate.candidateId())) {
                throw new IllegalArgumentException("实验候选不能为空或编号重复");
            }
            Set<String> allowed = ALLOWED_KEYS.get(candidate.group());
            if (!allowed.containsAll(candidate.parameterOverrides().keySet())) {
                throw new IllegalArgumentException("实验候选包含其他变量组的参数");
            }
        }
        baselineParameters = Map.copyOf(baselineParameters);
        candidates = List.copyOf(candidates);
    }

    /** 把指定候选覆盖到基线之上，返回可直接写入报告的完整参数快照。 */
    public Map<String, String> resolve(String candidateId) {
        RetrievalExperimentCandidate candidate = candidates.stream()
                .filter(value -> value.candidateId().equals(candidateId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("实验候选不存在"));
        Map<String, String> resolved = new LinkedHashMap<>(baselineParameters);
        resolved.putAll(candidate.parameterOverrides());
        return Map.copyOf(resolved);
    }

    /** 定义每个变量组允许覆盖的稳定参数名称。 */
    private static Map<RetrievalExperimentGroup, Set<String>> allowedKeys() {
        Map<RetrievalExperimentGroup, Set<String>> values =
                new EnumMap<>(RetrievalExperimentGroup.class);
        values.put(RetrievalExperimentGroup.ANALYZER, Set.of("analysisProfile"));
        values.put(RetrievalExperimentGroup.RECALL_BREADTH,
                Set.of("bm25TopK", "vectorTopK", "vectorCandidates",
                        "vectorMinimumSimilarity"));
        values.put(RetrievalExperimentGroup.FUSION,
                Set.of("rrfK", "fusionTopK", "rerankTopK"));
        values.put(RetrievalExperimentGroup.FIELD_WEIGHT,
                Set.of("titleWeight", "headingWeight", "contentWeight", "exactTermWeight"));
        values.put(RetrievalExperimentGroup.GROUNDED_THRESHOLD,
                Set.of("rerankGroundedThreshold"));
        return Map.copyOf(values);
    }
}
