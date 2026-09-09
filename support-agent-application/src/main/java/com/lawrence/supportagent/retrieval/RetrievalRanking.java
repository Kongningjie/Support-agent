package com.lawrence.supportagent.retrieval;

import java.util.List;

/**
 * 一次评测检索返回的有序候选及各分支状态。
 *
 * @param mode 本次执行的固定检索模式
 * @param candidates 最多十个通过来源有效性回查的有序候选
 * @param bm25Status BM25 分支执行状态
 * @param vectorStatus 向量分支执行状态
 * @param rerankStatus 重排序分支执行状态
 */
public record RetrievalRanking(RetrievalMode mode, List<RetrievalEvidence> candidates,
                               BranchStatus bm25Status, BranchStatus vectorStatus,
                               BranchStatus rerankStatus) {
}
