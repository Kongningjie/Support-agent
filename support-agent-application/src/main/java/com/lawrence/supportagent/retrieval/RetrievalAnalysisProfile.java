package com.lawrence.supportagent.retrieval;

/** 阶段七允许对比且不依赖额外分词组件的 Elasticsearch 文本分析方案。 */
public enum RetrievalAnalysisProfile {
    /** 仅使用阶段六基线 ICU 分析器。 */
    ICU_ONLY,
    /** 保留 ICU 主字段，并增加 Elasticsearch 内置 CJK 辅助字段。 */
    ICU_WITH_CJK
}
