package com.lawrence.supportagent.sharedkernel.error;

/** 应用基础层可稳定暴露的通用错误码。 */
public enum ErrorCode {
    /** 输入字段或请求组合不满足约束。 */
    COMMON_VALIDATION_FAILED,
    /** 请求与当前资源状态或版本冲突。 */
    COMMON_CONFLICT,
    /** 未预期的服务端内部错误。 */
    COMMON_INTERNAL_ERROR,
    /** MySQL、Redis、Elasticsearch 等外部依赖不可用。 */
    DEPENDENCY_UNAVAILABLE,
    /** 当前环境需要但未配置 DashScope 密钥。 */
    DASHSCOPE_NOT_CONFIGURED
}
