package com.lawrence.supportagent.chat;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** 保存单次聊天请求的首次连接取消原因，不依赖 Web 或模型框架。 */
public final class ChatExecutionControl {
    private final AtomicReference<CancellationReason> reason = new AtomicReference<>();

    /** 首次取消生效，后续回调不能覆盖原因；不执行存储或网络操作。 */
    public void cancel(CancellationReason cancellationReason) {
        reason.compareAndSet(null, Objects.requireNonNull(cancellationReason));
    }

    /** 返回首次取消原因；尚未取消时为空。 */
    public CancellationReason cancellationReason() { return reason.get(); }

    /** 返回连接是否已经报告取消，不表示供应商推理已经停止。 */
    public boolean isCancelled() { return reason.get() != null; }

    /** 固定连接终止原因，仅供内部清理和审计使用，不新增对外错误码。 */
    public enum CancellationReason {
        /** 连接异常或发送失败。 */ CHAT_CLIENT_DISCONNECTED,
        /** SSE 响应生命周期超时。 */ CHAT_RESPONSE_TIMEOUT,
        /** 有界队列或写入确认超过慢客户端保护期限。 */ CHAT_CLIENT_TOO_SLOW
    }
}
