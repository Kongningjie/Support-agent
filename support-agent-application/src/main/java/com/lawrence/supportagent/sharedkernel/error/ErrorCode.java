package com.lawrence.supportagent.sharedkernel.error;

/** 应用基础层可稳定暴露的通用错误码。 */
public enum ErrorCode {
    /** 输入字段或请求组合不满足约束。 */
    COMMON_VALIDATION_FAILED,
    /** 请求与当前资源状态或版本冲突。 */
    COMMON_CONFLICT,
    /** 同一幂等操作仍在执行且租约尚未过期。 */
    COMMON_IDEMPOTENCY_IN_PROGRESS,
    /** 同一个幂等键被用于不同的请求内容。 */
    COMMON_IDEMPOTENCY_KEY_REUSED,
    /** 未预期的服务端内部错误。 */
    COMMON_INTERNAL_ERROR,
    /** 用户名或密码错误且不区分具体原因。 */
    AUTH_INVALID_CREDENTIALS,
    /** Bearer Token 缺失、无效或已过期。 */
    AUTH_UNAUTHORIZED,
    /** 已认证用户没有执行当前操作的权限。 */
    AUTH_FORBIDDEN,
    /** 同一用户名或来源的登录失败次数达到限流阈值。 */
    AUTH_RATE_LIMITED,
    /** 管理员创建的用户名已经存在。 */
    AUTH_USERNAME_CONFLICT,
    /** 指定用户不存在。 */
    AUTH_USER_NOT_FOUND,
    /** 用户乐观锁版本已经变化。 */
    AUTH_USER_VERSION_CONFLICT,
    /** 管理员试图禁用自己的账号。 */
    AUTH_SELF_DISABLE_FORBIDDEN,
    /** 管理员试图降低自己的角色。 */
    AUTH_SELF_ROLE_CHANGE_FORBIDDEN,
    /** 新密码与当前密码相同。 */
    AUTH_PASSWORD_REUSED,
    /** 当前 Token 仅允许完成强制改密流程。 */
    AUTH_PASSWORD_CHANGE_REQUIRED,
    /** 指定长期记忆不存在或不属于当前用户。 */
    MEMORY_NOT_FOUND,
    /** 长期记忆或开关的乐观锁版本已经变化。 */
    MEMORY_VERSION_CONFLICT,
    /** 长期记忆当前状态不允许执行请求操作。 */
    MEMORY_STATUS_CONFLICT,
    /** 长期记忆正文包含凭据或个人敏感信息。 */
    MEMORY_SENSITIVE_CONTENT,
    /** 长期记忆正文与同用户同类型的现有记录重复。 */
    MEMORY_DUPLICATE_CONTENT,
    /** 指定工单不存在。 */
    TICKET_NOT_FOUND,
    /** 工单当前状态不允许执行请求操作。 */
    TICKET_STATUS_CONFLICT,
    /** 工单版本已变化，当前写入不能覆盖新版本。 */
    TICKET_VERSION_CONFLICT,
    /** 指定托管文档或知识来源不存在。 */
    KNOWLEDGE_NOT_FOUND,
    /** 相同规范化内容的有效知识已经存在。 */
    KNOWLEDGE_DUPLICATE_CONTENT,
    /** 文档包含疑似密钥、令牌或连接凭据。 */
    KNOWLEDGE_SENSITIVE_CONTENT,
    /** 文档当前状态不允许执行请求操作。 */
    KNOWLEDGE_STATUS_CONFLICT,
    /** 文档乐观锁版本已变化。 */
    KNOWLEDGE_VERSION_CONFLICT,
    /** 文档索引任务最终失败。 */
    KNOWLEDGE_INDEX_FAILED,
    /** 指定异步任务不存在。 */
    ASYNC_TASK_NOT_FOUND,
    /** 异步任务或其关联业务对象不再允许人工重试。 */
    ASYNC_TASK_NOT_RETRYABLE,
    /** MySQL、Redis、Elasticsearch 等外部依赖不可用。 */
    DEPENDENCY_UNAVAILABLE,
    /** 当前环境需要但未配置 DashScope 密钥。 */
    DASHSCOPE_NOT_CONFIGURED,
    /** 会话版本与服务端当前版本不一致。 */
    CHAT_VERSION_CONFLICT,
    /** 指定的后续会话已经过期或不存在。 */
    CHAT_CONVERSATION_EXPIRED,
    /** 同一会话已有未过期的运行。 */
    CHAT_CONVERSATION_BUSY,
    /** 相同客户端消息编号承载了不同内容。 */
    CHAT_MESSAGE_ID_REUSED,
    /** 混合检索的两个召回分支均不可用。 */
    RETRIEVAL_FAILED,
    /** 模型不可用、超时或返回了无效结果。 */
    CHAT_MODEL_UNAVAILABLE,
    /** 重生成后答案仍未通过安全校验。 */
    CHAT_ANSWER_VALIDATION_FAILED,
    /** 工单建议不存在、已过期或不属于当前会话。 */
    TICKET_SUGGESTION_NOT_FOUND,
    /** 工单建议正在被另一个请求消费。 */
    TICKET_SUGGESTION_IN_PROGRESS
}
