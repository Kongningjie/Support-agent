package com.lawrence.supportagent.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 保存一次不可变阶段七参数实验报告，不包含问题、知识正文或模型输出。
 *
 * @param reportId 本次实验报告的唯一标识
 * @param schemaVersion 报告结构版本
 * @param gitCommit 运行代码对应的 Git 提交
 * @param datasetVersion 优化开发集版本
 * @param datasetSha256 优化开发集内容哈希
 * @param startedAt 实验开始 UTC 时间
 * @param finishedAt 实验完成 UTC 时间
 * @param matrix 本次遵循的分组参数矩阵
 * @param modelNames 本次实际使用的 Embedding 与 Rerank 模型名称
 * @param results 按矩阵顺序保存的候选结果
 */
public record RetrievalExperimentReport(
        UUID reportId, String schemaVersion, String gitCommit,
        String datasetVersion, String datasetSha256,
        Instant startedAt, Instant finishedAt,
        RetrievalExperimentMatrix matrix, Map<String, String> modelNames,
        List<RetrievalExperimentCandidateResult> results) {
    /** 校验复现信息、时间顺序和候选结果均完整，并冻结结果集合。 */
    public RetrievalExperimentReport {
        if (reportId == null || schemaVersion == null || schemaVersion.isBlank()
                || gitCommit == null || gitCommit.isBlank()
                || datasetVersion == null || datasetVersion.isBlank()
                || datasetSha256 == null || !datasetSha256.matches("[0-9a-f]{64}")
                || startedAt == null || finishedAt == null || finishedAt.isBefore(startedAt)
                || matrix == null || modelNames == null || modelNames.isEmpty()
                || results == null || results.isEmpty()) {
            throw new IllegalArgumentException("检索实验报告字段不完整或时间范围不合法");
        }
        List<String> expectedIds = matrix.candidates().stream()
                .map(RetrievalExperimentCandidate::candidateId).toList();
        List<String> actualIds = results.stream()
                .map(RetrievalExperimentCandidateResult::candidateId).toList();
        if (!expectedIds.equals(actualIds)) {
            throw new IllegalArgumentException("实验结果必须完整遵循参数矩阵顺序");
        }
        for (RetrievalExperimentCandidateResult result : results) {
            if (!matrix.resolve(result.candidateId()).equals(result.resolvedParameters())) {
                throw new IllegalArgumentException("实验结果参数与矩阵候选不一致");
            }
        }
        modelNames = Map.copyOf(modelNames);
        results = List.copyOf(results);
    }
}
