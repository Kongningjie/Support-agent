package com.lawrence.supportagent.evaluation;

/** 区分禁止调参污染的锁定回归集与允许实验的优化开发集。 */
public enum EvaluationDatasetKind {
    /** 现有五十条，只用于回归和最终验收。 */ LOCKED_REGRESSION,
    /** 新增一百五十条，只用于阶段七参数实验。 */ OPTIMIZATION_DEVELOPMENT
}
