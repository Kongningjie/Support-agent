package com.lawrence.supportagent.security;

/** 标识统一输出安全网关正在检查的稳定模型或系统输出分支。 */
public enum ModelOutputType {
    /** 服务端固定回答，不经过模型生成。 */
    FIXED,
    /** 问候模型回答。 */
    GREETING,
    /** 基于知识证据的 RAG 回答。 */
    GROUNDED,
    /** 只读工单 Agent 回答。 */
    TICKET_ANSWER,
    /** 根据会话建议生成的工单草稿。 */
    TICKET_DRAFT,
    /** 根据已解决工单生成的案例草稿。 */
    RESOLVED_CASE_DRAFT
}
