package com.lawrence.supportagent.model;

/** 表示经过脱敏和可重试分类的外部模型调用失败。 */
public class ModelInvocationException extends RuntimeException {
    private final String errorCode;
    private final boolean retryable;

    /** 使用稳定错误码、安全消息和可重试标志创建模型异常。 */
    public ModelInvocationException(String errorCode, String safeMessage,
                                    boolean retryable, Throwable cause) {
        super(safeMessage, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    /** 返回稳定且不含供应商原始响应的错误码。 */
    public String errorCode() {
        return errorCode;
    }

    /** 返回调用是否适合按持久化任务退避重试。 */
    public boolean retryable() {
        return retryable;
    }
}
