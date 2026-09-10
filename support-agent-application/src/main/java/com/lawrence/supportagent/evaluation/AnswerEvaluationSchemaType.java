package com.lawrence.supportagent.evaluation;

/** 指定单条回答评测用例应走的输出契约。 */
public enum AnswerEvaluationSchemaType {
    /** 必须带合法证据编号的知识回答。 */ GROUNDED_ANSWER,
    /** 无知识时由应用层产生的固定克制响应。 */ NO_KNOWLEDGE,
    /** 只包含标题和问题字段的案例草稿。 */ RESOLVED_CASE
}
