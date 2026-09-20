package com.lawrence.supportagent.ticket;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.chat.port.ConversationStorePort;
import com.lawrence.supportagent.chat.port.ConversationStorePort.SuggestionClaim;
import com.lawrence.supportagent.model.ChatModelPort;
import com.lawrence.supportagent.model.ChatModelPort.TicketDraft;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import java.util.UUID;

/** 消费冻结的会话建议，在事务外生成内容并幂等创建唯一工单草稿。 */
public class SuggestedTicketUseCase {
    private final ConversationStorePort conversations;
    private final ChatModelPort model;
    private final TicketCommandUseCase commands;
    private final TicketQueryUseCase queries;
    private final TimeProvider time;

    /** 注入建议存储、模型、工单命令与查询用例。 */
    public SuggestedTicketUseCase(ConversationStorePort conversations, ChatModelPort model,
                                  TicketCommandUseCase commands, TicketQueryUseCase queries,
                                  TimeProvider time) {
        this.conversations = conversations; this.model = model; this.commands = commands;
        this.queries = queries; this.time = time;
    }

    /** 原子取得建议、至多重试一次模型生成并返回首次创建的工单。 */
    public TicketDetails create(AuthenticatedUser actor, UUID conversationId, UUID suggestionId,
                                String idempotencyKey) {
        if (actor == null) {
            throw new ApplicationException(ErrorCode.AUTH_UNAUTHORIZED, "认证信息无效或已经过期");
        }
        SuggestionClaim claim = conversations.claimSuggestion(actor.userId(), conversationId,
                suggestionId, time.now());
        if ("CONSUMED".equals(claim.status())) return queries.get(actor, claim.ticketNo());
        TicketDraft draft = draft(claim.frozenContext());
        TicketDetails ticket = commands.createSuggestedDraft(actor, conversationId, claim.sourceTurnId(),
                draft.title(), draft.problemDescription(), draft.attemptedActions(), idempotencyKey);
        conversations.consumeSuggestion(actor.userId(), conversationId, suggestionId,
                claim.claimId(), ticket.ticketNo(), time.now());
        return ticket;
    }

    /** 完整失败后只重试一次工单草稿模型。 */
    private TicketDraft draft(String context) {
        try { return model.generateTicketDraft(context); }
        catch (RuntimeException first) {
            try { return model.generateTicketDraft(context); }
            catch (RuntimeException second) {
                throw new ApplicationException(ErrorCode.CHAT_MODEL_UNAVAILABLE, "工单草稿模型暂时不可用");
            }
        }
    }
}
