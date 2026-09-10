package com.lawrence.supportagent.retrieval;

import java.util.List;

/**
 * 一次评测检索返回的有序候选及各分支状态。
 *
 * @param mode 本次执行的固定检索模式
 * @param candidates 最多 rankedTopK 个通过来源有效性回查的原始有序候选
 * @param reliableEvidence 应用生产可靠性规则后最多 finalTopK 个最终证据
 * @param status 应用可靠性规则后得到的检索三态
 * @param decisionReason 接受、拒绝或失败的可解释原因
 * @param bm25Status BM25 分支执行状态
 * @param vectorStatus 向量分支执行状态
 * @param rerankStatus 重排序分支执行状态
 */
public record RetrievalRanking(RetrievalMode mode, List<RetrievalEvidence> candidates,
                               List<RetrievalEvidence> reliableEvidence,
                               RetrievalStatus status,
                               RetrievalDecisionReason decisionReason,
                               BranchStatus bm25Status, BranchStatus vectorStatus,
                               BranchStatus rerankStatus) {
    /** 校验评测排名的分支状态和集合完整，并冻结两个候选集合。 */
    public RetrievalRanking {
        if (mode == null || candidates == null || reliableEvidence == null || status == null
                || decisionReason == null || bm25Status == null || vectorStatus == null
                || rerankStatus == null) {
            throw new IllegalArgumentException("检索排名字段不能为空");
        }
        candidates = List.copyOf(candidates);
        reliableEvidence = List.copyOf(reliableEvidence);
    }
}
