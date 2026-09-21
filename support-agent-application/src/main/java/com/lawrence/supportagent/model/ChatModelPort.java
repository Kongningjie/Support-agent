package com.lawrence.supportagent.model;

import com.lawrence.supportagent.retrieval.RetrievalEvidence;
import com.lawrence.supportagent.ticket.TicketDetails;
import java.util.List;

/** 隔离问候、知识回答和工单草稿等 Chat 模型能力。 */
public interface ChatModelPort {
    /** 生成简短问候，并在内部流式接收但只返回完整结果。 */
    ModelAnswer greeting(String message, List<String> recentTurns, Runnable firstTokenCallback);

    /** 使用单次泄漏标记和低基数反馈生成简短问候。 */
    default ModelAnswer greeting(String message, List<String> recentTurns,
                                 Runnable firstTokenCallback,
                                 ModelInvocationSecurity security) {
        return greeting(message, recentTurns, firstTokenCallback);
    }

    /** 依据唯一允许的证据生成带引用 Markdown 回答。 */
    ModelAnswer groundedAnswer(String message, List<String> recentTurns,
                               List<RetrievalEvidence> evidence, String validationFeedback,
                               Runnable firstTokenCallback);

    /** 使用单次泄漏标记和低基数反馈生成带引用回答。 */
    default ModelAnswer groundedAnswer(String message, List<String> recentTurns,
                                       List<RetrievalEvidence> evidence,
                                       Runnable firstTokenCallback,
                                       ModelInvocationSecurity security) {
        return groundedAnswer(message, recentTurns, evidence, security.feedback(),
                firstTokenCallback);
    }

    /** 使用 AgentScope ReActAgent 和受控只读工具回答指定工单。 */
    ModelAnswer ticketAnswer(String message, String allowedTicketNo, TicketDetails ticket,
                             List<String> recentTurns, Runnable firstTokenCallback);

    /** 使用单次泄漏标记和低基数反馈回答指定工单。 */
    default ModelAnswer ticketAnswer(String message, String allowedTicketNo,
                                     TicketDetails ticket, List<String> recentTurns,
                                     Runnable firstTokenCallback,
                                     ModelInvocationSecurity security) {
        return ticketAnswer(message, allowedTicketNo, ticket, recentTurns, firstTokenCallback);
    }

    /** 从冻结对话上下文生成结构化工单草稿。 */
    TicketDraft generateTicketDraft(String frozenContext);

    /** 使用单次泄漏标记和低基数反馈生成结构化工单草稿。 */
    default TicketDraft generateTicketDraft(String frozenContext,
                                            ModelInvocationSecurity security) {
        return generateTicketDraft(frozenContext);
    }

    /** 仅根据已解决工单事实生成结构化案例标题和问题描述。 */
    ResolvedCaseDraft generateResolvedCaseDraft(String ticketFacts);

    /** 使用单次泄漏标记和低基数反馈生成结构化案例草稿。 */
    default ResolvedCaseDraft generateResolvedCaseDraft(String ticketFacts,
                                                        ModelInvocationSecurity security) {
        return generateResolvedCaseDraft(ticketFacts);
    }

    /** @param text 完整模型正文 @param promptVersion Prompt 短哈希版本 @param serializedAgentState AgentScope 不透明状态 */
    record ModelAnswer(String text, String promptVersion, String serializedAgentState) {
        /** 创建不携带 Agent 状态的普通模型答案。 */
        public ModelAnswer(String text, String promptVersion) {
            this(text, promptVersion, null);
        }
    }

    /** @param title 标题 @param problemDescription 问题描述 @param attemptedActions 已尝试操作 */
    record TicketDraft(String title, String problemDescription, String attemptedActions) { }

    /** @param title 案例标题 @param problem 问题现象和适用背景 */
    record ResolvedCaseDraft(String title, String problem) { }
}
