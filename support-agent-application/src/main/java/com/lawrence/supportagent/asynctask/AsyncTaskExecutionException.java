package com.lawrence.supportagent.asynctask;

/** 由任务处理器抛出的安全、可分类执行异常。 */
public class AsyncTaskExecutionException extends RuntimeException {
    private final String errorCode;
    private final boolean retryable;

    /** 使用稳定错误码、脱敏消息和可重试标志创建异常。 */
    public AsyncTaskExecutionException(String errorCode, String safeMessage, boolean retryable) {
        super(safeMessage);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    /** 返回不会包含第三方原始正文的稳定错误码。 */
    public String errorCode() {
        return errorCode;
    }

    /** 返回失败是否允许按固定退避继续尝试。 */
    public boolean retryable() {
        return retryable;
    }
}
