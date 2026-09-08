package com.lawrence.supportagent.retrieval.port;

import com.lawrence.supportagent.retrieval.RetrievalEvidence;
import java.util.List;

/** 隔离 Elasticsearch 的 BM25 与向量召回实现。 */
public interface KnowledgeSearchPort {
    /** 使用加权全文和精确词查询返回最多 limit 个候选。 */
    List<RetrievalEvidence> searchBm25(String query, int limit);

    /** 使用查询向量和最低余弦相似度返回最多 limit 个候选。 */
    List<RetrievalEvidence> searchVector(List<Double> vector, int limit,
                                         int numberOfCandidates, double minimumSimilarity);
}
