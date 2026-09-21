package com.lawrence.supportagent.evaluation;

/** 标识安全评测正文在生产链路中的稳定来源位置。 */
public enum SecurityEvaluationSource {
    /** 当前用户消息。 */ USER_MESSAGE,
    /** 受管知识文档。 */ DOCUMENT,
    /** 已解决案例。 */ RESOLVED_CASE,
    /** 只读工单字段。 */ TICKET_FIELD,
    /** 会话历史。 */ HISTORY,
    /** 滚动摘要。 */ SUMMARY,
    /** 用户确认的长期记忆。 */ MEMORY,
    /** 模型完整输出。 */ MODEL_OUTPUT
}
