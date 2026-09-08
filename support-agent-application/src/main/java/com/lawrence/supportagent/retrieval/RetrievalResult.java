package com.lawrence.supportagent.retrieval;

import java.util.List;

/**
 * 汇总检索三态、各分支状态和最终安全证据。
 *
 * @param status 最终检索三态
 * @param bm25Status BM25 分支状态
 * @param vectorStatus 向量分支状态
 * @param rerankStatus 重排状态
 * @param evidence 最多五个最终证据
 * @param candidates 最多三十个安全候选摘要
 * @param durationMs 检索耗时毫秒
 */
public record RetrievalResult(RetrievalStatus status, BranchStatus bm25Status,
                              BranchStatus vectorStatus, BranchStatus rerankStatus,
                              List<RetrievalEvidence> evidence,
                              List<RetrievalEvidence> candidates, long durationMs) { }
