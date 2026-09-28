package com.lawrence.supportagent.evaluation;

import java.util.List;

/** 加载阶段 20 固定、只读的知识空间隔离评测数据集。 */
public interface KnowledgeSpaceEvaluationDatasetPort {
    /** 返回通过数量、分布和字段校验的全部固定用例。 */
    List<KnowledgeSpaceEvaluationCase> load();
}
