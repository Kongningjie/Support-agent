package com.lawrence.supportagent.chat;

/** 接收已经安全构造的业务事件，并允许接口层报告客户端断开。 */
public interface ChatEventSink {
    /** 发送一个业务事件；无法继续发送时抛出运行时异常。 */
    void send(ChatEvent event);

    /** 发送不占用业务序号的传输层心跳。 */
    void heartbeat();

    /** 正常关闭 SSE 连接。 */
    void complete();
}
