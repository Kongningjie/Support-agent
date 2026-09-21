package com.lawrence.supportagent.security;

/** 标识 Prompt 安全策略正在评估的不可信内容来源。 */
public enum PromptSecuritySource {
    /** 当前用户直接提交的消息。 */
    USER_MESSAGE,
    /** 当前会话中的历史成功轮次。 */
    HISTORY,
    /** 模型生成并通过结构校验的滚动摘要。 */
    SUMMARY,
    /** 用户确认后允许跨会话注入的长期记忆。 */
    MEMORY,
    /** 混合检索返回的知识文档或案例分块。 */
    EVIDENCE,
    /** 只读工单工具可返回的业务字段。 */
    TICKET_FIELD
}
