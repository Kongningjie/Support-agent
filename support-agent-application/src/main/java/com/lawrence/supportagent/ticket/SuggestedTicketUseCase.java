package com.lawrence.supportagent.ticket;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.chat.port.ConversationStorePort;
import com.lawrence.supportagent.chat.port.ConversationStorePort.SuggestionClaim;
import com.lawrence.supportagent.model.ChatModelPort;
import com.lawrence.supportagent.model.ChatModelPort.TicketDraft;
import com.lawrence.supportagent.model.ModelInvocationSecurity;
import com.lawrence.supportagent.security.ModelOutputAction;
import com.lawrence.supportagent.security.ModelOutputAssessment;
import com.lawrence.supportagent.security.ModelOutputSecurityService;
import com.lawrence.supportagent.security.ModelOutputType;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 消费冻结的会话建议，在事务外生成内容并幂等创建唯一工单草稿。 */
public class SuggestedTicketUseCase {
    private final ConversationStorePort conversations;
    private final ChatModelPort model;
    private final TicketCommandUseCase commands;
    private final TicketQueryUseCase queries;
    private final TimeProvider time;
    private final ModelOutputSecurityService outputSecurity;

    /** 注入建议存储、模型、工单命令与查询用例。 */
    public SuggestedTicketUseCase(ConversationStorePort conversations, ChatModelPort model,
                                  TicketCommandUseCase commands, TicketQueryUseCase queries,
                                  TimeProvider time) {
        this(conversations, model, commands, queries, time,
                ModelOutputSecurityService.standard(UUID::randomUUID));
    }

    /** 注入阶段 16 统一输出安全网关和全部既有依赖。 */
    public SuggestedTicketUseCase(ConversationStorePort conversations, ChatModelPort model,
                                  TicketCommandUseCase commands, TicketQueryUseCase queries,
                                  TimeProvider time, ModelOutputSecurityService outputSecurity) {
        this.conversations = conversations; this.model = model; this.commands = commands;
        this.queries = queries; this.time = time;
        this.outputSecurity = Objects.requireNonNull(outputSecurity, "模型输出安全服务不能为空");
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

    /** 完整调用失败或输出可修复时至多重试一次，任何失败正文均不持久化。 */
    private TicketDraft draft(String context) {
        int regenerations = 0;
        List<String> feedback = List.of();
        RuntimeException firstFailure = null;
        while (true) {
            ModelInvocationSecurity invocation = outputSecurity.newInvocation(feedback);
            try {
                TicketDraft generated = model.generateTicketDraft(context, invocation);
                ModelOutputAssessment assessment = outputSecurity.assess(
                        ModelOutputType.TICKET_DRAFT, draftText(generated), invocation,
                        currentUserMessage(context), List.of());
                if (assessment.action() == ModelOutputAction.PASS) {
                    return generated;
                }
                if (assessment.action() == ModelOutputAction.REGENERATE
                        && outputSecurity.canRegenerate(regenerations)) {
                    outputSecurity.recordRegeneration(ModelOutputType.TICKET_DRAFT);
                    regenerations++;
                    feedback = assessment.feedbackRules();
                    continue;
                }
                outputSecurity.recordFinalRejection(ModelOutputType.TICKET_DRAFT);
                throw new ApplicationException(ErrorCode.CHAT_ANSWER_VALIDATION_FAILED,
                        "工单草稿未通过安全校验");
            } catch (ApplicationException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                if (firstFailure == null && outputSecurity.canRegenerate(regenerations)) {
                    firstFailure = exception;
                    regenerations++;
                    continue;
                }
                throw new ApplicationException(ErrorCode.CHAT_MODEL_UNAVAILABLE,
                        "工单草稿模型暂时不可用");
            }
        }
    }

    /** 将结构化草稿合并为仅用于内存安全检查的完整文本。 */
    private String draftText(TicketDraft draft) {
        if (draft == null || draft.title() == null || draft.title().isBlank()
                || draft.title().length() > 160
                || draft.problemDescription() == null || draft.problemDescription().isBlank()
                || draft.problemDescription().length() > 8000
                || draft.attemptedActions() != null && draft.attemptedActions().length() > 8000) {
            return null;
        }
        return String.join("\n", String.valueOf(draft.title()),
                String.valueOf(draft.problemDescription()), String.valueOf(draft.attemptedActions()));
    }

    /** 从冻结建议上下文中提取触发建议的当前用户消息，限定邮箱原样返回授权。 */
    private String currentUserMessage(String context) {
        String marker = "用户当前问题：";
        int position = context == null ? -1 : context.lastIndexOf(marker);
        return position < 0 ? null : context.substring(position + marker.length());
    }
}
