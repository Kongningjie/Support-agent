package com.lawrence.supportagent.chat;

import com.lawrence.supportagent.chat.port.ConversationStorePort.Citation;
import com.lawrence.supportagent.retrieval.RetrievalStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 用户可见的单个成功会话轮次，不包含模型内部状态或建议冻结上下文。
 *
 * @param turnId 成功轮次 UUID
 * @param userMessage 用户输入正文
 * @param answer 已通过校验的完整回答
 * @param intent 最终意图
 * @param retrievalStatus 检索结果三态；非技术支持回答可为空
 * @param citations 最终公开引用
 * @param completedAt 轮次完成 UTC 时间
 * @param conversationVersion 本轮成功提交后的会话版本
 */
public record ConversationTurnView(UUID turnId, String userMessage, String answer,
                                   ChatIntent intent, RetrievalStatus retrievalStatus,
                                   List<Citation> citations, Instant completedAt,
                                   long conversationVersion) {
    /** 复制引用并拒绝缺少用户可见内容的轮次。 */
    public ConversationTurnView {
        if (turnId == null || userMessage == null || answer == null || intent == null
                || completedAt == null || conversationVersion < 1) {
            throw new IllegalArgumentException("会话轮次公开字段不完整");
        }
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
