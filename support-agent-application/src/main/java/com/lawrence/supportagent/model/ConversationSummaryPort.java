package com.lawrence.supportagent.model;

import com.lawrence.supportagent.chat.ConversationSummary;
import com.lawrence.supportagent.chat.port.ConversationStorePort.CompletedTurn;
import java.util.List;

/** 隔离结构化会话摘要生成模型，不与普通 Chat 或 AgentScope 状态绑定。 */
public interface ConversationSummaryPort {
    /** 根据已有摘要和新增成功轮次生成覆盖到目标版本的新结构化摘要。 */
    ConversationSummary summarize(ConversationSummary previousSummary,
                                  List<CompletedTurn> sourceTurns,
                                  long nextSummaryVersion,
                                  long coveredThroughVersion);
}
