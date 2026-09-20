package com.lawrence.supportagent.agent.model;

import com.lawrence.supportagent.chat.ConversationSummary;
import com.lawrence.supportagent.chat.port.ConversationStorePort.CompletedTurn;
import com.lawrence.supportagent.model.ConversationSummaryPort;
import com.lawrence.supportagent.model.ModelInvocationException;
import java.util.List;

/** 在未配置 DashScope 密钥时拒绝真实会话摘要调用。 */
public class UnavailableConversationSummaryAdapter implements ConversationSummaryPort {
    /** {@inheritDoc} */
    @Override
    public ConversationSummary summarize(ConversationSummary previousSummary,
                                         List<CompletedTurn> sourceTurns,
                                         long nextSummaryVersion,
                                         long coveredThroughVersion) {
        throw new ModelInvocationException("DASHSCOPE_NOT_CONFIGURED",
                "当前环境未配置会话摘要模型", false, null);
    }
}
