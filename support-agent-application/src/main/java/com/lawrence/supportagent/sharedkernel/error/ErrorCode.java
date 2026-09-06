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
    /** 指定工单不存在。 */
    TICKET_NOT_FOUND,
    /** 工单当前状态不允许执行请求操作。 */
    TICKET_STATUS_CONFLICT,
    /** 工单版本已变化，当前写入不能覆盖新版本。 */
    TICKET_VERSION_CONFLICT,
    /** 指定异步任务不存在。 */
    ASYNC_TASK_NOT_FOUND,
    /** 异步任务或其关联业务对象不再允许人工重试。 */
    ASYNC_TASK_NOT_RETRYABLE,
    /** MySQL、Redis、Elasticsearch 等外部依赖不可用。 */
    DEPENDENCY_UNAVAILABLE,
    /** 当前环境需要但未配置 DashScope 密钥。 */
    DASHSCOPE_NOT_CONFIGURED
}
