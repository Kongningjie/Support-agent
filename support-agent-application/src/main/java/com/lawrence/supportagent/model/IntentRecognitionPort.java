package com.lawrence.supportagent.model;

import com.lawrence.supportagent.chat.IntentDecision;
import com.lawrence.supportagent.chat.ChatExecutionControl;
import java.util.List;

/** 隔离结构化意图识别模型，不向应用层暴露模型 SDK 类型。 */
public interface IntentRecognitionPort {
    /** 根据当前消息和有限历史生成严格结构化意图。 */
    default IntentDecision recognize(String message, List<String> recentTurns) {
        return recognize(message, recentTurns, new ChatExecutionControl());
    }

    /** 在线调用必须传播当前请求取消，离线调用由兼容入口创建独立控制。 */
    IntentDecision recognize(String message, List<String> recentTurns, ChatExecutionControl control);
}
