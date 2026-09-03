package com.lawrence.supportagent.sharedkernel.error;

/** 携带稳定错误码且允许映射为公开响应的应用异常。 */
public class ApplicationException extends RuntimeException {
    private final ErrorCode errorCode;

    /** 使用稳定错误码和安全消息创建异常。 */
    public ApplicationException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /** 返回稳定错误码。 */
    public ErrorCode errorCode() {
        return errorCode;
    }
}
