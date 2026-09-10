package com.lawrence.supportagent.evaluation;

/** 加载阶段 8 独立、版本化的固定回答评测数据。 */
public interface AnswerEvaluationDatasetPort {
    /** 返回已经完整治理校验的数据快照。 */
    AnswerEvaluationDatasetSnapshot load();
}
