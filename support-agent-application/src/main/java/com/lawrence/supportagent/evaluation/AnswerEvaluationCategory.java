package com.lawrence.supportagent.evaluation;

/** 固定回答评测集中的互斥业务类别。 */
public enum AnswerEvaluationCategory {
    /** 单一可靠知识回答。 */ GROUNDED,
    /** 精确值保真回答。 */ EXACT,
    /** 无可靠知识时的克制响应。 */ NO_HIT,
    /** 托管文档与历史案例冲突披露。 */ CONFLICT,
    /** 已解决工单的案例结构化生成。 */ CASE_GENERATION
}
