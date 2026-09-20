package com.lawrence.supportagent.chat;

import java.util.List;

/**
 * 表示一次模型调用可安全使用的单会话上下文窗口。
 *
 * @param modelContext 先摘要后完整轮次的模型上下文片段
 * @param estimatedTokens 上下文与调用固定内容的保守估算 Token
 * @param summaryVersion 本次使用的摘要版本，尚无摘要时为零
 * @param coveredThroughVersion 摘要覆盖到的成功会话版本，尚无摘要时为零
 */
public record ConversationContext(List<String> modelContext, int estimatedTokens,
                                  long summaryVersion, long coveredThroughVersion) {
    /** 复制上下文片段并拒绝负数统计值。 */
    public ConversationContext {
        modelContext = modelContext == null ? List.of() : List.copyOf(modelContext);
        if (estimatedTokens < 0 || summaryVersion < 0 || coveredThroughVersion < 0) {
            throw new IllegalArgumentException("会话上下文统计值不能为负数");
        }
    }
}
