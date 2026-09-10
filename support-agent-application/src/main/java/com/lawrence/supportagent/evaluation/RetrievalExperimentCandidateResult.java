package com.lawrence.supportagent.evaluation;

import java.util.List;
import java.util.Map;

/**
 * 保存一个参数候选的质量、可靠性与延迟结果，不负责自动选择生产参数。
 *
 * @param candidateId 对应参数矩阵中的稳定候选编号
 * @param resolvedParameters 本次实际使用的完整参数快照
 * @param metrics 五项冻结检索质量指标
 * @param diagnostics 可靠性门槛混淆计数与分类分数范围
 * @param retrievalLatency 检索端到端延迟摘要
 * @param caseResults 不包含问题正文的逐条排名和可靠性判断结果
 */
public record RetrievalExperimentCandidateResult(
        String candidateId, Map<String, String> resolvedParameters,
        RetrievalEvaluationMetrics metrics, RetrievalEvaluationDiagnostics diagnostics,
        LatencySummary retrievalLatency,
        List<RetrievalEvaluationCaseResult> caseResults) {
    /** 校验候选结果完整并复制参数映射，防止报告生成后被修改。 */
    public RetrievalExperimentCandidateResult {
        if (candidateId == null || candidateId.isBlank()
                || resolvedParameters == null || resolvedParameters.isEmpty()
                || metrics == null || diagnostics == null || retrievalLatency == null
                || caseResults == null || caseResults.isEmpty()) {
            throw new IllegalArgumentException("实验候选结果不能为空");
        }
        resolvedParameters = Map.copyOf(resolvedParameters);
        caseResults = List.copyOf(caseResults);
    }
}
