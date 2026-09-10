package com.lawrence.supportagent.evaluation;

/** 阶段七每次只允许改变的一组可解释检索变量。 */
public enum RetrievalExperimentGroup {
    /** ICU 与内置 CJK 辅助字段的分析配置。 */
    ANALYZER,
    /** BM25、向量召回数量和向量候选池。 */
    RECALL_BREADTH,
    /** RRF、融合数量和 Rerank 候选数量。 */
    FUSION,
    /** 标题、标题路径、正文和精确词的有限权重。 */
    FIELD_WEIGHT,
    /** 最终可靠知识判断使用的单一全局阈值。 */
    GROUNDED_THRESHOLD
}
