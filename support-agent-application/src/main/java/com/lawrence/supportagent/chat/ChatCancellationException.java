package com.lawrence.supportagent.chat;

/** 内部请求取消，不代表模型不可用，也不是新增 HTTP/SSE 错误契约。 */
public final class ChatCancellationException extends RuntimeException {
    private final ChatExecutionControl.CancellationReason reason;

    /** 保存固定终止原因，不携带用户或供应商正文。 */
    public ChatCancellationException(ChatExecutionControl.CancellationReason reason) {
        super("聊天请求已经取消");
        this.reason = java.util.Objects.requireNonNull(reason);
    }

    /** 返回原始连接终止分类，供业务清理使用。 */
    public ChatExecutionControl.CancellationReason reason() { return reason; }
}
