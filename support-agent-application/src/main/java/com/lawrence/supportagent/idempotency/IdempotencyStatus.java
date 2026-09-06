package com.lawrence.supportagent.idempotency;

/** 外部写请求幂等记录的稳定持久化状态。 */
public enum IdempotencyStatus {
    /** 当前请求已取得执行权。 */
    PROCESSING,
    /** 首次业务操作已成功提交。 */
    SUCCEEDED,
    /** 首次操作遇到瞬时故障，可在后续请求中恢复。 */
    FAILED_RETRYABLE,
    /** 首次操作遇到确定性错误，后续请求复用该错误。 */
    FAILED_FINAL
}
