package com.lawrence.supportagent.retrieval;

/** 固定检索评测允许使用的四种候选排序模式。 */
public enum RetrievalMode {
    /** 仅使用 Elasticsearch BM25 关键词召回。 */
    BM25_ONLY,
    /** 仅使用查询向量语义召回。 */
    VECTOR_ONLY,
    /** 使用 BM25、向量召回和 RRF 融合。 */
    HYBRID,
    /** 使用混合召回后再调用模型重排序。 */
    HYBRID_RERANK
}
