package com.lawrence.supportagent.agent.model;

import com.lawrence.supportagent.model.ChatModelPort;
import com.lawrence.supportagent.model.ModelInvocationException;
import com.lawrence.supportagent.retrieval.RetrievalEvidence;
import com.lawrence.supportagent.ticket.TicketDetails;
import java.util.List;

/** 在开发环境未配置密钥时延迟报告模型能力不可用，使固定分支仍可启动。 */
public class UnavailableChatModelAdapter implements ChatModelPort {
    /** {@inheritDoc} */ @Override public ModelAnswer greeting(String message, List<String> turns, Runnable callback) { throw unavailable(); }
    /** {@inheritDoc} */ @Override public ModelAnswer groundedAnswer(String message, List<String> turns,
            List<RetrievalEvidence> evidence, String feedback, Runnable callback) { throw unavailable(); }
    /** {@inheritDoc} */ @Override public ModelAnswer ticketAnswer(String message, String ticketNo,
            TicketDetails ticket, List<String> turns, Runnable callback) { throw unavailable(); }
    /** {@inheritDoc} */ @Override public TicketDraft generateTicketDraft(String context) { throw unavailable(); }
    /** {@inheritDoc} */ @Override public ResolvedCaseDraft generateResolvedCaseDraft(String context) { throw unavailable(); }
    /** 创建不包含密钥或内部地址的稳定异常。 */
    private ModelInvocationException unavailable() { return new ModelInvocationException(
            "DASHSCOPE_NOT_CONFIGURED", "当前环境未配置 DashScope 密钥", false, null); }
}
