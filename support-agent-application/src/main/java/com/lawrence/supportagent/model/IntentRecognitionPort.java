package com.lawrence.supportagent.model;

import com.lawrence.supportagent.chat.IntentDecision;
import java.util.List;

/** 隔离结构化意图识别模型，不向应用层暴露模型 SDK 类型。 */
public interface IntentRecognitionPort {
    /** 根据当前消息和有限历史生成严格结构化意图。 */
    IntentDecision recognize(String message, List<String> recentTurns);
}
