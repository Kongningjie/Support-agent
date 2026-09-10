package com.lawrence.supportagent.evaluation;

import com.lawrence.supportagent.retrieval.RetrievalDecisionReason;
import com.lawrence.supportagent.retrieval.RetrievalStatus;
import java.util.List;

/**
 * 保存单条评测用例的预期、实际状态和有序来源。
 *
 * @param caseId 对应固定用例的稳定标识
 * @param expectedStatus 人工标注的预期三态结果
 * @param actualStatus 本次检索产生的实际三态结果
 * @param rankedSourceKeys 前十候选按首次出现去重后的稳定来源键
 * @param exactTermsSatisfied 前五候选是否覆盖全部必需精确词
 * @param highestRerankScore 原始候选中的最高 Rerank 分数；未执行或降级时为空
 * @param reliableEvidenceCount 应用生产可靠性规则后保留的证据数量
 * @param decisionReason 最终接受、拒绝或失败的稳定原因
 * @param failureMessage 单条执行失败的脱敏摘要，正常完成时为空
 * @param retrievalDurationMs 单条检索的端到端耗时毫秒
 */
public record RetrievalEvaluationCaseResult(String caseId, RetrievalStatus expectedStatus,
                                            RetrievalStatus actualStatus,
                                            List<String> rankedSourceKeys,
                                            boolean exactTermsSatisfied,
                                            Double highestRerankScore,
                                            int reliableEvidenceCount,
                                            RetrievalDecisionReason decisionReason,
                                            String failureMessage,
                                            long retrievalDurationMs) {
    /** 校验状态、分数和计数范围，并冻结有序来源键集合。 */
    public RetrievalEvaluationCaseResult {
        if (caseId == null || caseId.isBlank() || expectedStatus == null || actualStatus == null
                || rankedSourceKeys == null || reliableEvidenceCount < 0
                || decisionReason == null || retrievalDurationMs < 0
                || highestRerankScore != null && (!Double.isFinite(highestRerankScore)
                || highestRerankScore < 0 || highestRerankScore > 1)) {
            throw new IllegalArgumentException("单条检索评测结果字段不合法");
        }
        rankedSourceKeys = List.copyOf(rankedSourceKeys);
    }

    /** 兼容既有测试构造方式，缺少实测耗时时使用零。 */
    public RetrievalEvaluationCaseResult(String caseId, RetrievalStatus expectedStatus,
                                         RetrievalStatus actualStatus,
                                         List<String> rankedSourceKeys,
                                         boolean exactTermsSatisfied, String failureMessage) {
        this(caseId, expectedStatus, actualStatus, rankedSourceKeys, exactTermsSatisfied,
                null, actualStatus == RetrievalStatus.GROUNDED ? 1 : 0,
                actualStatus == RetrievalStatus.GROUNDED
                        ? RetrievalDecisionReason.RELIABLE_EVIDENCE_PRESENT
                        : RetrievalDecisionReason.NO_CANDIDATE,
                failureMessage, 0);
    }
}
