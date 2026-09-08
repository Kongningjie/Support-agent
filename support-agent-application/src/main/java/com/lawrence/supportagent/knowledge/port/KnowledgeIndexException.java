package com.lawrence.supportagent.knowledge.port;

/** 表示经过脱敏和重试分类的 Elasticsearch 索引操作失败。 */
public class KnowledgeIndexException extends RuntimeException {
    private final String errorCode;
    private final boolean retryable;

    /** 使用稳定错误码、安全消息和可重试标志创建索引异常。 */
    public KnowledgeIndexException(String errorCode, String safeMessage,
                                   boolean retryable, Throwable cause) {
        super(safeMessage, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    /** 返回不会泄露内部地址或原始响应的稳定错误码。 */
    public String errorCode() {
        return errorCode;
    }

    /** 返回错误是否适合按固定退避重试。 */
    public boolean retryable() {
        return retryable;
    }
}
