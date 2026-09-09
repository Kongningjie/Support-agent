package com.lawrence.supportagent.evaluation;

/** 创建不含正文的评测运行元数据快照。 */
public interface EvaluationRuntimeMetadataPort {
    /** 根据已经锁定的数据快照生成本次不可变运行上下文。 */
    RetrievalEvaluationContext create(RetrievalEvaluationDatasetSnapshot dataset);
}
