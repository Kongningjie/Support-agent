package com.lawrence.supportagent.chat;

import com.lawrence.supportagent.chat.port.AgentAuditPort;
import com.lawrence.supportagent.chat.port.ConversationStorePort;
import com.lawrence.supportagent.chat.port.ConversationStorePort.BeginResult;
import com.lawrence.supportagent.chat.port.ConversationStorePort.Citation;
import com.lawrence.supportagent.chat.port.ConversationStorePort.CompletedTurn;
import com.lawrence.supportagent.chat.port.ConversationStorePort.CommitResolution;
import com.lawrence.supportagent.chat.port.ConversationStorePort.CommitStatus;
import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.model.ChatModelPort;
import com.lawrence.supportagent.model.ChatModelPort.ModelAnswer;
import com.lawrence.supportagent.model.ModelInvocationSecurity;
import com.lawrence.supportagent.model.ModelInvocationException;
import com.lawrence.supportagent.memory.UserMemoryCandidateService;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpace;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceAccessService;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort.Operation;
import com.lawrence.supportagent.retrieval.RetrievalEvidence;
import com.lawrence.supportagent.retrieval.RetrievalAccessContext;
import com.lawrence.supportagent.retrieval.RetrievalResult;
import com.lawrence.supportagent.retrieval.RetrievalStatus;
import com.lawrence.supportagent.retrieval.RetrievalService;
import com.lawrence.supportagent.security.DeterministicPromptSecurityPolicy;
import com.lawrence.supportagent.security.LlmSecuritySettings;
import com.lawrence.supportagent.security.ModelOutputAction;
import com.lawrence.supportagent.security.ModelOutputAssessment;
import com.lawrence.supportagent.security.ModelOutputSecurityService;
import com.lawrence.supportagent.security.ModelOutputType;
import com.lawrence.supportagent.security.PromptSecurityPolicy;
import com.lawrence.supportagent.security.PromptSecuritySource;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.sharedkernel.port.UuidGenerator;
import com.lawrence.supportagent.ticket.TicketDetails;
import com.lawrence.supportagent.ticket.TicketQueryUseCase;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** 编排意图、检索、受控模型、确定性校验、会话提交和 SSE 事件。 */
public class ChatUseCase {
    private static final System.Logger LOGGER = System.getLogger(ChatUseCase.class.getName());
    private static final int MODEL_CONTEXT_TURNS = 6;
    private static final int MODEL_CONTEXT_CHARACTERS = 12_000;
    private static final int MAXIMUM_ANSWER_CHARACTERS = 8_000;
    private static final int SAFE_FRAGMENT_CHARACTERS = 300;
    private static final Duration DEFAULT_SUGGESTION_TTL = Duration.ofHours(24);
    private static final String NO_KNOWLEDGE = "当前知识库中没有找到足够可靠的依据，因此我不能给出确定的处理步骤。你可以确认创建一个技术支持工单，由人工继续处理。";
    private static final String OUT_OF_SCOPE = "我只能协助企业内部技术支持、知识问答和工单查询。请提供相关技术问题或工单编号。";
    private static final String TICKET_REQUIRED = "请提供格式为 T 加 12 位数字的工单编号。";
    private static final String SECURITY_REDACTED = "[内容已因安全策略隐藏]";
    private static final PromptSecurityPolicy DEFAULT_PROMPT_SECURITY =
            new DeterministicPromptSecurityPolicy(new LlmSecuritySettings(true, true, true));
    private final IntentRecognitionService intents;
    private final RetrievalService retrieval;
    private final ChatModelPort chatModel;
    private final TicketQueryUseCase tickets;
    private final ConversationStorePort conversations;
    private final AgentAuditPort audits;
    private final UuidGenerator ids;
    private final TimeProvider time;
    private final String chatModelName;
    private final String embeddingModelName;
    private final String rerankModelName;
    private final double groundedThreshold;
    private final OptimizationTelemetryPort telemetry;
    private final Duration suggestionTtl;
    private final ConversationContextService contextService;
    private final UserMemoryCandidateService memoryCandidates;
    private final PromptSecurityPolicy promptSecurity;
    private final ModelOutputSecurityService outputSecurity;
    private final KnowledgeSpaceAccessService spaceAccess;

    /** 创建不向 Agent 下放检索路由或写权限的聊天用例。 */
    public ChatUseCase(IntentRecognitionService intents, RetrievalService retrieval,
                       ChatModelPort chatModel, TicketQueryUseCase tickets,
                       ConversationStorePort conversations, AgentAuditPort audits,
                       AnswerValidator validator, UuidGenerator ids, TimeProvider time,
                       String chatModelName, String embeddingModelName,
                       String rerankModelName, double groundedThreshold) {
        this(intents, retrieval, chatModel, tickets, conversations, audits, validator, ids, time,
                chatModelName, embeddingModelName, rerankModelName, groundedThreshold,
                OptimizationTelemetryPort.noOp(), DEFAULT_SUGGESTION_TTL, null);
    }

    /** 创建带二期低基数耗时遥测的聊天用例。 */
    public ChatUseCase(IntentRecognitionService intents, RetrievalService retrieval,
                       ChatModelPort chatModel, TicketQueryUseCase tickets,
                       ConversationStorePort conversations, AgentAuditPort audits,
                       AnswerValidator validator, UuidGenerator ids, TimeProvider time,
                       String chatModelName, String embeddingModelName,
                       String rerankModelName, double groundedThreshold,
                       OptimizationTelemetryPort telemetry) {
        this(intents, retrieval, chatModel, tickets, conversations, audits, validator, ids, time,
                chatModelName, embeddingModelName, rerankModelName, groundedThreshold,
                telemetry, DEFAULT_SUGGESTION_TTL, null);
    }

    /** 创建带显式建议有效期和二期低基数耗时遥测的聊天用例。 */
    public ChatUseCase(IntentRecognitionService intents, RetrievalService retrieval,
                       ChatModelPort chatModel, TicketQueryUseCase tickets,
                       ConversationStorePort conversations, AgentAuditPort audits,
                       AnswerValidator validator, UuidGenerator ids, TimeProvider time,
                       String chatModelName, String embeddingModelName,
                       String rerankModelName, double groundedThreshold,
                       OptimizationTelemetryPort telemetry, Duration suggestionTtl) {
        this(intents, retrieval, chatModel, tickets, conversations, audits, validator, ids, time,
                chatModelName, embeddingModelName, rerankModelName, groundedThreshold,
                telemetry, suggestionTtl, null);
    }

    /** 创建带阶段 10 统一上下文组装、显式建议有效期和遥测的聊天用例。 */
    public ChatUseCase(IntentRecognitionService intents, RetrievalService retrieval,
                       ChatModelPort chatModel, TicketQueryUseCase tickets,
                       ConversationStorePort conversations, AgentAuditPort audits,
                       AnswerValidator validator, UuidGenerator ids, TimeProvider time,
                       String chatModelName, String embeddingModelName,
                       String rerankModelName, double groundedThreshold,
                       OptimizationTelemetryPort telemetry, Duration suggestionTtl,
                       ConversationContextService contextService) {
        this(intents, retrieval, chatModel, tickets, conversations, audits, validator, ids, time,
                chatModelName, embeddingModelName, rerankModelName, groundedThreshold,
                telemetry, suggestionTtl, contextService, null, DEFAULT_PROMPT_SECURITY);
    }

    /** 创建带阶段 13 长期记忆候选生成能力的聊天用例。 */
    public ChatUseCase(IntentRecognitionService intents, RetrievalService retrieval,
                       ChatModelPort chatModel, TicketQueryUseCase tickets,
                       ConversationStorePort conversations, AgentAuditPort audits,
                       AnswerValidator validator, UuidGenerator ids, TimeProvider time,
                       String chatModelName, String embeddingModelName,
                       String rerankModelName, double groundedThreshold,
                       OptimizationTelemetryPort telemetry, Duration suggestionTtl,
                       ConversationContextService contextService,
                       UserMemoryCandidateService memoryCandidates) {
        this(intents, retrieval, chatModel, tickets, conversations, audits, validator, ids, time,
                chatModelName, embeddingModelName, rerankModelName, groundedThreshold,
                telemetry, suggestionTtl, contextService, memoryCandidates,
                DEFAULT_PROMPT_SECURITY);
    }

    /** 创建带阶段 15 Prompt 信任边界和全部既有聊天能力的用例。 */
    public ChatUseCase(IntentRecognitionService intents, RetrievalService retrieval,
                       ChatModelPort chatModel, TicketQueryUseCase tickets,
                       ConversationStorePort conversations, AgentAuditPort audits,
                       AnswerValidator validator, UuidGenerator ids, TimeProvider time,
                       String chatModelName, String embeddingModelName,
                       String rerankModelName, double groundedThreshold,
                       OptimizationTelemetryPort telemetry, Duration suggestionTtl,
                       ConversationContextService contextService,
                       UserMemoryCandidateService memoryCandidates,
                       PromptSecurityPolicy promptSecurity) {
        this(intents, retrieval, chatModel, tickets, conversations, audits, validator, ids, time,
                chatModelName, embeddingModelName, rerankModelName, groundedThreshold,
                telemetry, suggestionTtl, contextService, memoryCandidates, promptSecurity,
                ModelOutputSecurityService.standard(validator, ids));
    }

    /** 创建带阶段 16 统一模型输出安全网关和全部既有聊天能力的用例。 */
    public ChatUseCase(IntentRecognitionService intents, RetrievalService retrieval,
                       ChatModelPort chatModel, TicketQueryUseCase tickets,
                       ConversationStorePort conversations, AgentAuditPort audits,
                       AnswerValidator validator, UuidGenerator ids, TimeProvider time,
                       String chatModelName, String embeddingModelName,
                       String rerankModelName, double groundedThreshold,
                       OptimizationTelemetryPort telemetry, Duration suggestionTtl,
                       ConversationContextService contextService,
                       UserMemoryCandidateService memoryCandidates,
                       PromptSecurityPolicy promptSecurity,
                       ModelOutputSecurityService outputSecurity) {
        this(intents, retrieval, chatModel, tickets, conversations, audits, validator, ids, time,
                chatModelName, embeddingModelName, rerankModelName, groundedThreshold,
                telemetry, suggestionTtl, contextService, memoryCandidates, promptSecurity,
                outputSecurity, null);
    }

    /** 创建带阶段 19 会话空间授权和全部既有聊天能力的用例。 */
    public ChatUseCase(IntentRecognitionService intents, RetrievalService retrieval,
                       ChatModelPort chatModel, TicketQueryUseCase tickets,
                       ConversationStorePort conversations, AgentAuditPort audits,
                       AnswerValidator validator, UuidGenerator ids, TimeProvider time,
                       String chatModelName, String embeddingModelName,
                       String rerankModelName, double groundedThreshold,
                       OptimizationTelemetryPort telemetry, Duration suggestionTtl,
                       ConversationContextService contextService,
                       UserMemoryCandidateService memoryCandidates,
                       PromptSecurityPolicy promptSecurity,
                       ModelOutputSecurityService outputSecurity,
                       KnowledgeSpaceAccessService spaceAccess) {
        if (suggestionTtl == null || suggestionTtl.isZero() || suggestionTtl.isNegative()) {
            throw new IllegalArgumentException("工单建议有效期必须大于 0");
        }
        this.intents = intents;
        this.retrieval = retrieval;
        this.chatModel = chatModel;
        this.tickets = tickets;
        this.conversations = conversations;
        this.audits = audits;
        this.ids = ids;
        this.time = time;
        this.chatModelName = chatModelName;
        this.embeddingModelName = embeddingModelName;
        this.rerankModelName = rerankModelName;
        this.groundedThreshold = groundedThreshold;
        this.telemetry = telemetry == null ? OptimizationTelemetryPort.noOp() : telemetry;
        this.suggestionTtl = suggestionTtl;
        this.contextService = contextService;
        this.memoryCandidates = memoryCandidates;
        this.promptSecurity = Objects.requireNonNull(promptSecurity, "Prompt 安全策略不能为空");
        this.outputSecurity = Objects.requireNonNull(outputSecurity, "模型输出安全服务不能为空");
        this.spaceAccess = spaceAccess;
    }

    /**
     * 在建立 SSE 响应前同步校验会话版本、消息幂等键和运行租约。
     *
     * @param request 已通过接口参数校验的聊天请求
     * @return 已取得运行权或已命中重放的准备结果
     */
    public PreparedChat prepare(ChatRequest request) {
        if (request.actor() == null) {
            throw new ApplicationException(ErrorCode.AUTH_UNAUTHORIZED, "认证信息无效或已经过期");
        }
        if (promptSecurity.assess(request.message(), PromptSecuritySource.USER_MESSAGE).blocked()) {
            throw new ApplicationException(ErrorCode.CHAT_PROMPT_INJECTION_BLOCKED,
                    "请求包含无法安全处理的指令");
        }
        UUID activeSpaceId = resolveActiveSpace(request);
        RetrievalAccessContext accessContext = spaceAccess == null
                ? new RetrievalAccessContext(request.actor(), activeSpaceId,
                activeSpaceId.equals(KnowledgeSpace.GLOBAL_SPACE_ID)
                        ? Set.of(KnowledgeSpace.GLOBAL_SPACE_ID)
                        : Set.of(KnowledgeSpace.GLOBAL_SPACE_ID, activeSpaceId))
                : spaceAccess.retrievalContext(request.actor(), activeSpaceId);
        ChatRequest normalized = new ChatRequest(request.actor(), activeSpaceId,
                request.conversationId(), request.clientMessageId(), request.message(),
                request.expectedConversationVersion());
        UUID runId = ids.generate();
        BeginResult begin = conversations.begin(request.actor().userId(), request.conversationId(),
                request.clientMessageId(), request.message(), activeSpaceId,
                request.expectedConversationVersion(), runId, time.now());
        return new PreparedChat(normalized, runId, begin, accessContext);
    }

    /** 解析新会话或既有会话的活动空间；新会话必须显式提供空间。 */
    private UUID resolveActiveSpace(ChatRequest request) {
        if (request.conversationId() == null) {
            if (request.spaceId() == null) {
                throw new ApplicationException(ErrorCode.KNOWLEDGE_SPACE_CONTEXT_REQUIRED,
                        "新会话必须选择知识空间");
            }
            return request.spaceId();
        }
        UUID storedSpaceId = conversations.lifecycleDetails(request.actor().userId(), false,
                request.conversationId(), 0, time.now()).spaceId();
        if (request.spaceId() != null && !request.spaceId().equals(storedSpaceId)) {
            throw new ApplicationException(ErrorCode.CHAT_SPACE_MISMATCH,
                    "请求空间与会话当前代际不一致");
        }
        return storedSpaceId;
    }

    /** 执行一次完整聊天运行，失败时不写半轮会话。 */
    public void stream(ChatRequest request, ChatEventSink sink) {
        stream(prepare(request), sink);
    }

    /**
     * 终结已准备但从未进入 stream 的运行；重放没有取得运行权，不能修改原结果。
     *
     * @param prepared 同步准备结果，只能由未启动任务的唯一处理路径调用
     * @param reason 连接取消原因；调度初始化失败但未报告连接取消时为空
     */
    public void failBeforeStart(PreparedChat prepared, ChatExecutionControl.CancellationReason reason) {
        if (prepared.begin().status() == ConversationStorePort.BeginStatus.REPLAY) return;
        String code = reason == null ? ErrorCode.COMMON_INTERNAL_ERROR.name() : reason.name();
        finishFailedRun(prepared, code, 0, true);
    }

    /** 分别尝试运行释放、审计创建与失败终结；首个异常保留，其余异常不阻断清理。 */
    private void finishFailedRun(PreparedChat prepared, String code, long durationMs, boolean createAudit) {
        UUID ownerUserId = prepared.request().actor().userId();
        RuntimeException failure = null;
        try {
            conversations.fail(ownerUserId, prepared.begin().conversationId(), prepared.runId(), time.now());
        } catch (RuntimeException exception) {
            failure = exception;
            LOGGER.log(System.Logger.Level.WARNING, "Chat cleanup stage=release result=failed reason={0}", code);
        }
        if (createAudit) {
            try {
                audits.start(prepared.runId(), ownerUserId, prepared.begin().conversationId(),
                        prepared.request().clientMessageId(), time.now());
            } catch (RuntimeException exception) {
                if (failure == null) failure = exception;
                LOGGER.log(System.Logger.Level.WARNING, "Chat cleanup stage=audit_start result=failed reason={0}", code);
            }
        }
        try {
            audits.fail(prepared.runId(), "FAILED", code, durationMs, time.now());
        } catch (RuntimeException exception) {
            if (failure == null) failure = exception;
            LOGGER.log(System.Logger.Level.WARNING, "Chat cleanup stage=audit_fail result=failed reason={0}", code);
        }
        if (failure != null) throw failure;
    }

    /**
     * 执行已完成同步准备的聊天运行。
     *
     * @param prepared 同步准备结果
     * @param sink SSE 事件接收端
     */
    public void stream(PreparedChat prepared, ChatEventSink sink) {
        stream(prepared, sink, new ChatExecutionControl());
    }

    /** 在业务阶段边界检查取消，并原子协调最终提交与响应不确定的核实。 */
    public void stream(PreparedChat prepared, ChatEventSink sink, ChatExecutionControl control) {
        long started = System.nanoTime();

        ChatRequest request = prepared.request();
        UUID runId = prepared.runId();
        BeginResult begin = prepared.begin();
        UUID ownerUserId = request.actor().userId();
        // 已完成的相同消息直接执行幂等重放，不再重复调用模型。
        if (begin.status() == ConversationStorePort.BeginStatus.REPLAY) {
            try {
                replay(ownerUserId, begin, sink, control);
                telemetry.recordDuration(Operation.CHAT_REQUEST, elapsed(started), true);
            } catch (ChatCancellationException cancelled) {
                // 重放未取得运行权，不清理原运行或改写原成功审计。
                telemetry.recordDuration(Operation.CHAT_REQUEST, elapsed(started), false);
            } finally {
                closeSink(sink);
            }
            return;
        }

        //表示：当前聊天结果有没有已经成功提交到会话存储。
        boolean committed = false;
        boolean commitEntered = false;
        CommitStatus commitStatus = CommitStatus.UNKNOWN;
        try {
            // 记录因租约过期而被当前请求接管的旧运行。
            if (begin.interruptedRunId() != null) {
                audits.fail(begin.interruptedRunId(), "INTERRUPTED", "CHAT_RUN_LEASE_EXPIRED", 0, time.now());
            }
            //记录当前 run 正式开始
            audits.start(runId, ownerUserId, begin.conversationId(), request.clientMessageId(), time.now());
            control.checkCancelled();
            //向前端发送 conversation.started
            emit(ownerUserId, sink, begin.conversationId(), runId, "conversation.started",
                    Map.of("conversationVersion", begin.version(), "replayed", false), control);
            //加载模型上下文
            List<String> context = modelContext(ownerUserId, begin.conversationId(), List.of(request.message()), control);
            //意图识别
            IntentDecision decision = intents.recognize(request.message(), context, control);
            control.checkCancelled();
            //记录意图识别结果
            audits.recordIntent(runId, decision);
            control.checkCancelled();
            //route()：真正执行业务
            Outcome outcome = route(request.actor(), prepared.accessContext(), begin.conversationId(),
                    runId, request.message(), context, decision, sink, control);
            control.checkCancelled();
            //构造 CompletedTurn  CompletedTurn pending 表示：已经生成完成，但是还没有正式提交到会话存储的一轮聊天结果。
            CompletedTurn pending = new CompletedTurn(ids.generate(), request.clientMessageId(), runId,
                    request.message().trim(), decision.standaloneQuery(), decision.intent(), outcome.answer,
                    outcome.retrievalStatus, outcome.citations, outcome.suggestionId,
                    suggestionContext(request.message(), context), outcome.resultStatus,
                    time.now(), begin.version() + 1);
            //发送正文前再次校验空间权限
            control.checkCancelled();
            revalidateBeforeAnswer(request.actor(), prepared.accessContext());
            control.checkCancelled();
            //开始把答案正文通过 SSE 发出去
            emitAnswerBody(ownerUserId, sink, begin.conversationId(), runId, pending, started, control);
            control.checkCancelled();
            //为后面的事件提前申请 sequence
            Long suggestionSequence = pending.suggestionId() == null ? null
                    : conversations.nextSequence(ownerUserId, begin.conversationId(), runId);
            control.checkCancelled();
            long completedSequence = conversations.nextSequence(ownerUserId, begin.conversationId(), runId);
            if (!control.tryEnterCommit()) {
                control.checkCancelled();
                throw new IllegalStateException("当前请求已经使用提交权");
            }
            commitEntered = true;
            // 门闩后不再以连接取消中断存储；网络异常先原子核实，不盲目 fail。
            CommitResolution resolution = commitOrResolve(prepared, pending, outcome.agentState);
            commitStatus = resolution.status();
            if (resolution.status() != CommitStatus.COMMITTED) {
                if (resolution.status() == CommitStatus.FAILED || resolution.status() == CommitStatus.REJECTED) {
                    boolean rejected = resolution.status() == CommitStatus.REJECTED;
                    ErrorCode code = rejected ? ErrorCode.CHAT_CONVERSATION_BUSY : ErrorCode.DEPENDENCY_UNAVAILABLE;
                    auditResolvedFailure(prepared, control, started, code);
                    if (!control.isCancelled()) {
                        emitPrepared(sink, begin.conversationId(), runId, completedSequence, "error", Map.of(
                                "code", code.name(), "message", rejected ? "会话运行权已经失效" : "会话结果未能保存，请稍后重试",
                                "retryable", !rejected), control);
                    }
                }
                telemetry.recordDuration(Operation.CHAT_REQUEST, elapsed(started), false);
                return;
            }
            CompletedTurn completed = resolution.turn();
            //标记已经提交成功
            committed = true;
            // 先尝试成功审计；故障告警不能抹掉 Redis 已保存的成功结果。
            auditCommittedSuccess(runId, outcome.promptVersion, started);
            control.checkCancelled();
            //发送 answer.completed
            emitAnswerCompleted(ownerUserId, sink, begin.conversationId(), runId, completed,
                    suggestionSequence, completedSequence, control);
            //记录 answer complete 指标  记录：从请求开始到完整答案完成一共多久。
            telemetry.recordDuration(Operation.ANSWER_COMPLETE, elapsed(started), true);
            //SSE 正常结束
            sink.complete();
            //聊天成功后更新上下文  一轮聊天成功后，更新会话上下文相关内容。
            if (contextService != null && !control.isCancelled()) {
                contextService.afterSuccessfulTurn(ownerUserId, begin.conversationId());
            }
            //生成长期记忆候选  聊天成功后，根据用户这次输入判断是否有值得长期保存的记忆候选。
            if (memoryCandidates != null && !control.isCancelled()) {
                memoryCandidates.afterSuccessfulTurn(ownerUserId, begin.conversationId(),
                        request.clientMessageId(), request.message());
            }
            //记录整个请求成功
            telemetry.recordDuration(Operation.CHAT_REQUEST, elapsed(started), true);
        } catch (ChatCancellationException exception) {
            if (committed) {
                telemetry.recordDuration(Operation.CHAT_REQUEST, elapsed(started), true);
            } else if (commitEntered) {
                // 提交协调已处理或尚不可确认，不重复执行普通失败清理。
                telemetry.recordDuration(Operation.CHAT_REQUEST, elapsed(started), false);
            } else {
                finishFailure(prepared, control, exception, started);
            }
        } catch (RuntimeException exception) {
            //如果已经提交成功，就不能再算失败
            if (committed) {
                telemetry.recordDuration(Operation.CHAT_REQUEST, elapsed(started), true);
                return;
            }
            if (commitEntered) {
                LOGGER.log(System.Logger.Level.WARNING, "Chat commit stage=finalize result=failed outcome={0}", commitStatus.name());
                telemetry.recordDuration(Operation.CHAT_REQUEST, elapsed(started), false);
                return;
            }
            if (!control.isCancelled()) {
                String code = failureCode(exception);
                try {
                    emit(ownerUserId, sink, begin.conversationId(), runId, "error", Map.of(
                            "code", code, "message", safeMessage(exception),
                            "retryable", retryable(code, exception)), control);
                } catch (RuntimeException ignored) {
                    // 发送失败不影响清理；若并发取消，以首次取消原因为准。
                }
            }
            finishFailure(prepared, control, exception, started);
        } finally {
            closeSink(sink);
        }
    }

    /** 提交异常时仅执行一次原子核实；核实不可用或归属不符时不猜测业务终态。 */
    private CommitResolution commitOrResolve(PreparedChat prepared, CompletedTurn pending, String agentState) {
        UUID owner = prepared.request().actor().userId();
        UUID conversationId = prepared.begin().conversationId();
        try {
            return new CommitResolution(CommitStatus.COMMITTED,
                    conversations.complete(owner, conversationId, prepared.runId(), pending, agentState, time.now()));
        } catch (RuntimeException exception) {
            if (exception instanceof ApplicationException application
                    && application.errorCode() == ErrorCode.CHAT_CONVERSATION_BUSY) {
                return new CommitResolution(CommitStatus.REJECTED, null);
            }
            LOGGER.log(System.Logger.Level.WARNING, "Chat commit stage=complete result=unconfirmed");
        }
        CommitResolution resolution;
        try {
            resolution = Objects.requireNonNull(conversations.resolveCommitOrFail(owner, conversationId,
                    prepared.request().clientMessageId(), prepared.runId(), prepared.begin().generation(), time.now()));
        } catch (RuntimeException exception) {
            resolution = new CommitResolution(CommitStatus.UNKNOWN, null);
        }
        LOGGER.log(System.Logger.Level.WARNING, "Chat commit stage=resolve result={0}", resolution.status().name());
        return resolution;
    }

    /** Redis 已经确认成功，审计故障仅告警，不能覆盖会话结果或再次尝试失败清理。 */
    private void auditCommittedSuccess(UUID runId, String promptVersion, long started) {
        try {
            audits.succeed(runId, promptVersion, chatModelName, embeddingModelName,
                    rerankModelName, elapsed(started), time.now());
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Chat commit stage=success_audit result=failed");
        }
    }

    /** Redis 已原子终结当前运行，只尝试失败审计，不再调用普通 fail。 */
    private void auditResolvedFailure(PreparedChat prepared, ChatExecutionControl control, long started, ErrorCode failureCode) {
        String code = control.cancellationReason() == null ? failureCode.name()
                : control.cancellationReason().name();
        try {
            audits.fail(prepared.runId(), "FAILED", code, elapsed(started), time.now());
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Chat commit stage=failure_audit result=failed");
        }
    }

    /** 发送端终结失败不能改变已经确定的业务终态。 */
    private void closeSink(ChatEventSink sink) {
        try {
            sink.complete();
        } catch (RuntimeException ignored) {
            LOGGER.log(System.Logger.Level.WARNING, "Chat cleanup stage=sink_close result=failed");
        }
    }

    /** 统一选择终止原因并独立清理；不因清理故障重新发送错误或再次终结运行。 */
    private void finishFailure(PreparedChat prepared, ChatExecutionControl control,
                               RuntimeException exception, long started) {
        String code = control.cancellationReason() != null ? control.cancellationReason().name()
                : exception instanceof ChatCancellationException cancelled
                ? cancelled.reason().name() : failureCode(exception);
        if (control.isCancelled() || exception instanceof ChatCancellationException) {
            LOGGER.log(System.Logger.Level.INFO, "Chat terminated reason={0}", code);
        }
        try {
            finishFailedRun(prepared, code, elapsed(started), false);
        } catch (RuntimeException ignored) {
            // 已逐项记录安全告警；租约机制负责存储不可用期间的运行恢复。
        }
        telemetry.recordDuration(Operation.CHAT_REQUEST, elapsed(started), false);
    }

    /** 从正常业务故障中提取固定错误码，不输出原始模型内容。 */
    private String failureCode(RuntimeException exception) {
        return exception instanceof ApplicationException application ? application.errorCode().name()
                : exception instanceof ModelInvocationException model ? model.errorCode() : "COMMON_INTERNAL_ERROR";
    }

    /** 在任何正文或引用发送前重新读取当前账号、空间和成员事实，权限变化时失败关闭。 */
    private void revalidateBeforeAnswer(AuthenticatedUser actor,
                                        RetrievalAccessContext accessContext) {
        if (spaceAccess == null) {
            return;
        }
        RetrievalAccessContext current = spaceAccess.retrievalContext(
                actor, accessContext.activeSpaceId());
        if (!current.allowedSpaceIds().equals(accessContext.allowedSpaceIds())) {
            throw new ApplicationException(ErrorCode.KNOWLEDGE_SPACE_NOT_FOUND,
                    "知识空间不可访问");
        }
    }

    /** 按应用层确定的意图执行唯一允许的分支。 */
    private Outcome route(com.lawrence.supportagent.auth.AuthenticatedUser actor,
                          RetrievalAccessContext accessContext,
                          UUID conversationId, UUID runId, String message, List<String> context,
                          IntentDecision decision, ChatEventSink sink, ChatExecutionControl control) {
        control.checkCancelled();
        return switch (decision.intent()) {
            case OUT_OF_SCOPE -> fixed(OUT_OF_SCOPE, "OUT_OF_SCOPE", control);
            case GREETING -> greeting(actor.userId(), conversationId, runId, message, context, control);
            case TICKET_QUERY -> ticket(actor, conversationId, runId, message, context, decision, control);
            case SUPPORT_QUERY -> support(actor.userId(), accessContext, conversationId, runId,
                    message, context, decision, sink, control);
        };
    }

    /** 调用问候模型并记录真实首 Token 回调耗时。 */
    private Outcome greeting(UUID ownerUserId, UUID conversationId, UUID runId,
                             String message, List<String> context, ChatExecutionControl control) {
        int regenerations = 0;
        List<String> feedback = List.of();
        while (true) {
            control.checkCancelled();
            ModelInvocationSecurity invocation = outputSecurity.newInvocation(feedback);
            long modelStarted = System.nanoTime();
            control.checkCancelled();
            ModelAnswer answer = chatModel.greeting(message, context,
                    firstTokenCallback(ownerUserId, conversationId, runId, modelStarted, control), invocation, control);
            control.checkCancelled();
            ModelOutputAssessment assessment = outputSecurity.assess(
                    ModelOutputType.GREETING, answer == null ? null : answer.text(),
                    invocation, message, List.of());
            control.checkCancelled();
            if (assessment.action() == ModelOutputAction.PASS) {
                return fromModel(answer, "GREETING", null, List.of(), null, null);
            }
            if (assessment.action() == ModelOutputAction.REGENERATE
                    && outputSecurity.canRegenerate(regenerations)) {
                outputSecurity.recordRegeneration(ModelOutputType.GREETING);
                regenerations++;
                feedback = assessment.feedbackRules();
                continue;
            }
            outputSecurity.recordFinalRejection(ModelOutputType.GREETING);
            throw outputValidationFailure();
        }
    }

    /** 执行仅允许一次指定工单读取的 AgentScope 分支。 */
    private Outcome ticket(com.lawrence.supportagent.auth.AuthenticatedUser actor,
                           UUID conversationId, UUID runId, String message, List<String> context,
                           IntentDecision decision, ChatExecutionControl control) {
        control.checkCancelled();
        if (decision.ticketNo() == null) return fixed(TICKET_REQUIRED, "TICKET_NUMBER_REQUIRED", control);
        try {
            TicketDetails ticket = tickets.get(actor, decision.ticketNo());
            control.checkCancelled();
            ticket = secureTicket(ticket);
            List<String> answerContext = modelContext(actor.userId(), conversationId,
                    ticketFixedSections(message, ticket), control);
            int regenerations = 0;
            List<String> feedback = List.of();
            while (true) {
                control.checkCancelled();
                ModelInvocationSecurity invocation = outputSecurity.newInvocation(feedback);
                control.checkCancelled();
                ModelAnswer answer = chatModel.ticketAnswer(message, decision.ticketNo(), ticket,
                        answerContext, () -> renew(actor.userId(), conversationId, runId, control), invocation, control);
                control.checkCancelled();
                ModelOutputAssessment assessment = outputSecurity.assess(
                        ModelOutputType.TICKET_ANSWER, answer == null ? null : answer.text(),
                        invocation, message, List.of());
                control.checkCancelled();
                if (assessment.action() == ModelOutputAction.PASS) {
                    return fromModel(answer, "TICKET_FOUND", null, List.of(), null,
                            answer.serializedAgentState());
                }
                if (assessment.action() == ModelOutputAction.REGENERATE
                        && outputSecurity.canRegenerate(regenerations)) {
                    outputSecurity.recordRegeneration(ModelOutputType.TICKET_ANSWER);
                    regenerations++;
                    feedback = assessment.feedbackRules();
                    continue;
                }
                outputSecurity.recordFinalRejection(ModelOutputType.TICKET_ANSWER);
                throw outputValidationFailure();
            }
        } catch (ApplicationException exception) {
            control.checkCancelled();
            if (exception.errorCode() == ErrorCode.TICKET_NOT_FOUND) {
                return fixed("未找到工单 " + decision.ticketNo() + "，请核对编号。",
                        "TICKET_NOT_FOUND", control);
            }
            throw exception;
        }
    }

    /** 执行完整 RAG，并严格区分无知识与检索技术故障。 */
    private Outcome support(UUID ownerUserId, RetrievalAccessContext accessContext,
                            UUID conversationId, UUID runId,
                            String message, List<String> context,
                            IntentDecision decision, ChatEventSink sink, ChatExecutionControl control) {
        //先告诉前端：开始检索
        emit(ownerUserId, sink, conversationId, runId, "retrieval.started", Map.of("mode", "HYBRID"), control);
        //真正执行 RAG 检索
        RetrievalResult result = retrieval.retrieve(decision.standaloneQuery(), accessContext);
        control.checkCancelled();
        result = secureRetrieval(result);
        control.checkCancelled();
        //记录检索审计
        audits.recordRetrieval(runId, decision.standaloneQuery(), result, groundedThreshold, time.now());
        control.checkCancelled();
        //准备 retrieval.completed 的 SSE 数据
        Map<String, Object> completed = new LinkedHashMap<>();
        completed.put("status", result.status().name());
        completed.put("bm25Status", result.bm25Status().name());
        completed.put("vectorStatus", result.vectorStatus().name());
        completed.put("rerankStatus", result.rerankStatus().name());
        //最终选中了多少条证据。
        completed.put("selectedCount", result.evidence().size());
        //整个检索耗时。
        completed.put("durationMs", result.durationMs());
        emit(ownerUserId, sink, conversationId, runId, "retrieval.completed", completed, control);
        //检索系统本身出故障了。
        if (result.status() == RetrievalStatus.RETRIEVAL_FAILED) {
            throw new ApplicationException(ErrorCode.RETRIEVAL_FAILED, "知识检索暂时不可用");
        }
        //检索正常完成，但没有找到足够可靠的知识。
        if (result.status() == RetrievalStatus.NO_RELIABLE_KNOWLEDGE) {
            return new Outcome(NO_KNOWLEDGE, "no-knowledge-v1", "NO_RELIABLE_KNOWLEDGE",
                    RetrievalStatus.NO_RELIABLE_KNOWLEDGE, List.of(), ids.generate(), null);
        }
        //有可靠知识，开始构造 Citation
        List<Citation> citations = citations(result.evidence(), accessContext.allowedSpaceIds());
        //重新准备回答模型的上下文  这次必须预留：当前用户问题+本次 RAG 检索证据
        List<String> answerContext = modelContext(ownerUserId, conversationId, fixedSections(message, result.evidence()), control);
        //初始化“重生成次数”
        int regenerations = 0;
        List<String> feedback = List.of();
        while (true) {
            control.checkCancelled();
            //为本次模型调用创建安全上下文
            ModelInvocationSecurity invocation = outputSecurity.newInvocation(feedback);
            //记录模型开始时间
            long modelStarted = System.nanoTime();
            //真正调用回答模型
            control.checkCancelled();
            ModelAnswer answer = chatModel.groundedAnswer(message, answerContext,
                    result.evidence(), firstTokenCallback(ownerUserId, conversationId, runId,
                            modelStarted, control), invocation, control);
            control.checkCancelled();
            //给答案补 Disclosure
            answer = answer == null ? null : withDisclosure(answer, result.evidence());
            //对模型输出做安全校验
            control.checkCancelled();
            ModelOutputAssessment assessment = outputSecurity.assess(
                    ModelOutputType.GROUNDED, answer == null ? null : answer.text(),
                    invocation, message, result.evidence());
            control.checkCancelled();
            //PASS：直接返回答案
            if (assessment.action() == ModelOutputAction.PASS) {
                return fromModel(answer, "GROUNDED", RetrievalStatus.GROUNDED,
                        citations, null, null);
            }
            //REGENERATE：允许的话重新生成
            if (assessment.action() == ModelOutputAction.REGENERATE
                    && outputSecurity.canRegenerate(regenerations)) {
                //用于监控：Grounded Answer 因安全问题触发了一次重生成。
                outputSecurity.recordRegeneration(ModelOutputType.GROUNDED);
                regenerations++;
                feedback = assessment.feedbackRules();
                continue;
            }
            outputSecurity.recordFinalRejection(ModelOutputType.GROUNDED);
            throw outputValidationFailure();
        }
    }

    /** 对文档与已解决案例混合证据追加固定冲突边界说明。 */
    private ModelAnswer withDisclosure(ModelAnswer answer, List<RetrievalEvidence> evidence) {
        boolean documents = evidence.stream().anyMatch(item -> "MANAGED_DOCUMENT".equals(item.sourceType()));
        boolean cases = evidence.stream().anyMatch(item -> "RESOLVED_CASE".equals(item.sourceType()));
        if (!documents || !cases) return answer;
        boolean exactConflict = evidence.stream().flatMap(item -> item.exactTerms().stream())
                .collect(java.util.stream.Collectors.groupingBy(term -> term.type(),
                        java.util.stream.Collectors.mapping(term -> term.normalizedValue(),
                                java.util.stream.Collectors.toSet())))
                .values().stream().anyMatch(values -> values.size() > 1);
        String notice = exactConflict
                ? "\n\n> 注意：知识文档与历史案例中的精确技术值存在差异，请先核对当前环境和版本，再执行相关命令。"
                : "\n\n> 注意：本回答同时参考了知识文档与历史已解决案例；案例描述的是特定环境，请以当前环境核验结果为准。";
        return new ModelAnswer(answer.text() + notice, answer.promptVersion(), answer.serializedAgentState());
    }

    /** 使用阶段 10 服务组装模型上下文；旧构造器仅供既有隔离测试保留原行为。 */
    private List<String> modelContext(UUID ownerUserId, UUID conversationId, List<String> fixedSections,
                                      ChatExecutionControl control) {
        control.checkCancelled();
        if (contextService == null) {
            //从会话存储中读取最近的聊天历史。
            List<String> recent = conversations.recentContext(ownerUserId, conversationId,
                            MODEL_CONTEXT_TURNS, MODEL_CONTEXT_CHARACTERS);
            control.checkCancelled();
            List<String> safe = recent.stream()
                    //过滤掉历史记录里可能存在的 Prompt Injection。
                    .filter(value -> !promptSecurity.assess(
                            value, PromptSecuritySource.HISTORY).blocked())
                    .toList();
            control.checkCancelled();
            return safe;
        }
        List<String> context = contextService.prepare(ownerUserId, conversationId, fixedSections).modelContext();
        control.checkCancelled();
        return context;
    }

    /** 排除本次检索中携带高置信度注入指令的证据并重新确定检索三态。 */
    private RetrievalResult secureRetrieval(RetrievalResult result) {
        //不是 GROUNDED，直接原样返回
        if (result.status() != RetrievalStatus.GROUNDED) {
            return result;
        }
        //开始过滤 Evidence
        List<RetrievalEvidence> evidence = result.evidence().stream()
                //只保留没有被 Prompt Security 判定为 BLOCK 的证据。
                .filter(item -> !promptSecurity.assess(
                        evidenceSecurityText(item), PromptSecuritySource.EVIDENCE).blocked())
                .toList();
        //重新计算最终 RetrievalStatus
        RetrievalStatus status = evidence.isEmpty()
                ? RetrievalStatus.NO_RELIABLE_KNOWLEDGE : RetrievalStatus.GROUNDED;
        return new RetrievalResult(status, result.bm25Status(), result.vectorStatus(),
                result.rerankStatus(), evidence, result.candidates(), result.durationMs());
    }

    /** 合并证据所有可控文本字段，防止标题或标题路径绕过正文检查。 */
    private String evidenceSecurityText(RetrievalEvidence evidence) {
        return String.join("\n", String.valueOf(evidence.title()),
                String.valueOf(evidence.headingPath()), String.valueOf(evidence.content()));
    }

    /** 仅在本次模型调用中隐藏包含高置信度注入指令的可变工单正文。 */
    private TicketDetails secureTicket(TicketDetails ticket) {
        return new TicketDetails(ticket.ticketNo(), ticket.spaceId(), ticket.space(),
                secureTicketField(ticket.title()),
                secureTicketField(ticket.problemDescription()),
                secureTicketField(ticket.attemptedActions()), ticket.status(),
                secureTicketField(ticket.rootCause()), secureTicketField(ticket.solution()),
                secureTicketField(ticket.closeReason()), ticket.version(), ticket.createdAt(),
                ticket.updatedAt(), ticket.resolvedAt(), ticket.closedAt());
    }

    /** 保留普通或模糊工单文本，只替换高置信度攻击字段。 */
    private String secureTicketField(String value) {
        if (value == null) {
            return null;
        }
        return promptSecurity.assess(value, PromptSecuritySource.TICKET_FIELD).blocked()
                ? SECURITY_REDACTED : value;
    }

    /** 汇总当前问题及完整证据内容，用于在回答模型调用前核算输入预算。 */
    private List<String> fixedSections(String message, List<RetrievalEvidence> evidence) {
        List<String> values = new ArrayList<>();
        values.add(message);
        evidence.stream().map(RetrievalEvidence::content).forEach(values::add);
        return List.copyOf(values);
    }

    /** 按工单工具真实公开字段核算固定输入，避免长字段绕过 Token 预算。 */
    private List<String> ticketFixedSections(String message, TicketDetails ticket) {
        return List.of(message, ticket.ticketNo(), ticket.title(), ticket.problemDescription(),
                String.valueOf(ticket.attemptedActions()), ticket.status().name(),
                String.valueOf(ticket.rootCause()), String.valueOf(ticket.solution()),
                String.valueOf(ticket.closeReason()));
    }

    /** 在落库前发送安全正文；客户端发送失败时不提交会话轮次。 */
    private void emitAnswerBody(UUID ownerUserId, ChatEventSink sink, UUID conversationId, UUID runId,
                                CompletedTurn turn, long requestStarted, ChatExecutionControl control) {
        emit(ownerUserId, sink, conversationId, runId, "answer.started", Map.of("contentType", "text/markdown"), control);
        boolean firstFragment = true;
        for (String fragment : fragments(turn.answer())) {
            emit(ownerUserId, sink, conversationId, runId, "answer.delta", Map.of("text", fragment), control);
            if (firstFragment) {
                telemetry.recordDuration(Operation.SAFE_FIRST_DELTA, elapsed(requestStarted), true);
                firstFragment = false;
            }
        }
        for (Citation citation : turn.citations()) {
            emit(ownerUserId, sink, conversationId, runId, "citation", citationData(citation), control);
        }
    }

    /** 在原子提交成功后发送带有新版本的完成事件。 */
    private void emitAnswerCompleted(UUID ownerUserId, ChatEventSink sink, UUID conversationId, UUID runId,
                                     CompletedTurn turn, Long suggestionSequence,
                                     long completedSequence, ChatExecutionControl control) {
        if (turn.suggestionId() != null) {
            emitPrepared(sink, conversationId, runId, suggestionSequence, "ticket.suggested", Map.of(
                    "suggestionId", turn.suggestionId().toString(),
                    "expiresAt", turn.completedAt().plus(suggestionTtl).toString()), control);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answer", turn.answer());
        data.put("citations", turn.citations().stream().map(this::citationData).toList());
        data.put("conversationVersion", turn.conversationVersion());
        data.put("resultStatus", turn.resultStatus());
        emitPrepared(sink, conversationId, runId, completedSequence, "answer.completed", data, control);
    }

    /** 重发成功消息而不执行模型、检索或增加会话版本。 */
    private void replay(UUID ownerUserId, BeginResult begin, ChatEventSink sink, ChatExecutionControl control) {
        CompletedTurn turn = begin.replayTurn();
        emitReplay(ownerUserId, sink, begin.conversationId(), turn.runId(), "conversation.started",
                Map.of("conversationVersion", begin.version(), "replayed", true), control);
        emitReplayAnswer(ownerUserId, sink, begin.conversationId(), turn.runId(), turn, control);
        sink.complete();
    }

    /** 使用新事件编号和新序号重放既有安全内容。 */
    private void emitReplayAnswer(UUID ownerUserId, ChatEventSink sink, UUID conversationId, UUID runId,
                                  CompletedTurn turn, ChatExecutionControl control) {
        emitReplay(ownerUserId, sink, conversationId, runId, "answer.started", Map.of("contentType", "text/markdown"), control);
        for (String fragment : fragments(turn.answer())) {
            emitReplay(ownerUserId, sink, conversationId, runId, "answer.delta", Map.of("text", fragment), control);
        }

        for (Citation citation : turn.citations()) {
            emitReplay(ownerUserId, sink, conversationId, runId, "citation", citationData(citation), control);
        }

        if (turn.suggestionId() != null) {
            emitReplay(ownerUserId, sink, conversationId, runId, "ticket.suggested", Map.of(
                    "suggestionId", turn.suggestionId().toString(),
                    "expiresAt", turn.completedAt().plus(suggestionTtl).toString()), control);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answer", turn.answer());
        data.put("citations", turn.citations().stream().map(this::citationData).toList());
        data.put("conversationVersion", turn.conversationVersion());
        data.put("resultStatus", turn.resultStatus());
        emitReplay(ownerUserId, sink, conversationId, runId, "answer.completed", data, control);
    }

    /** 为最终证据按顺序分配 S1 开始的临时引用。 */
    private List<Citation> citations(List<RetrievalEvidence> evidence,
                                     Set<UUID> allowedSpaceIds) {
        List<Citation> values = new ArrayList<>();
        for (int index = 0; index < evidence.size(); index++) {
            RetrievalEvidence item = evidence.get(index);
            if (item.spaceId() == null || !allowedSpaceIds.contains(item.spaceId())) {
                throw new ApplicationException(ErrorCode.RETRIEVAL_FAILED,
                        "检索证据空间校验失败");
            }
            values.add(new Citation("S" + (index + 1), item.spaceId(),
                    Long.toString(item.sourceId()), item.title(),
                    item.headingPath(), item.sourceType(), "RESOLVED_CASE".equals(item.sourceType())
                    ? Long.toString(item.sourceId()) : null));
        }
        return List.copyOf(values);
    }

    /** 构造固定安全回答结果。 */
    private Outcome fixed(String answer, String resultStatus, ChatExecutionControl control) {
        control.checkCancelled();
        ModelOutputAssessment assessment = outputSecurity.assess(
                ModelOutputType.FIXED, answer, ModelInvocationSecurity.none(), null, List.of());
        control.checkCancelled();
        if (assessment.action() != ModelOutputAction.PASS) {
            throw outputValidationFailure();
        }
        return new Outcome(answer, "fixed-v1", resultStatus, null, List.of(), null, null);
    }

    /** 创建不包含失败正文和规则细节的统一输出校验异常。 */
    private ApplicationException outputValidationFailure() {
        return new ApplicationException(ErrorCode.CHAT_ANSWER_VALIDATION_FAILED,
                "模型答案未通过安全校验");
    }

    /** 把完整模型结果转换为统一路由结果。 */
    private Outcome fromModel(ModelAnswer answer, String resultStatus,
                              RetrievalStatus retrievalStatus, List<Citation> citations,
                              UUID suggestionId, String agentState) {
        if (answer == null || answer.text() == null || answer.text().isBlank()
                || answer.text().length() > MAXIMUM_ANSWER_CHARACTERS) {
            throw new ApplicationException(ErrorCode.CHAT_MODEL_UNAVAILABLE, "Chat 模型返回无效结果");
        }
        return new Outcome(answer.text(), answer.promptVersion(), resultStatus,
                retrievalStatus, citations, suggestionId, agentState);
    }

    /** 原子分配序号并发送业务事件。 */
    private void emit(UUID ownerUserId, ChatEventSink sink, UUID conversationId, UUID runId,
                      String eventType, Map<String, Object> data, ChatExecutionControl control) {
        control.checkCancelled();
        long sequence = conversations.nextSequence(ownerUserId, conversationId, runId);
        control.checkCancelled();
        sink.send(new ChatEvent(ids.generate(), eventType, runId, conversationId,
                sequence, time.now(), Map.copyOf(data)));
        control.checkCancelled();
    }

    /** 发送不要求存在活动运行租约的重放事件。 */
    private void emitReplay(UUID ownerUserId, ChatEventSink sink, UUID conversationId, UUID runId,
                            String eventType, Map<String, Object> data, ChatExecutionControl control) {
        control.checkCancelled();
        long sequence = conversations.nextReplaySequence(ownerUserId, conversationId);
        control.checkCancelled();
        sink.send(new ChatEvent(ids.generate(), eventType, runId, conversationId,
                sequence, time.now(), Map.copyOf(data)));
        control.checkCancelled();
    }

    /** 使用提交前预留的序号发送提交后的终结事件。 */
    private void emitPrepared(ChatEventSink sink, UUID conversationId, UUID runId, long sequence,
                              String eventType, Map<String, Object> data, ChatExecutionControl control) {
        control.checkCancelled();
        sink.send(new ChatEvent(ids.generate(), eventType, runId, conversationId,
                sequence, time.now(), Map.copyOf(data)));
        control.checkCancelled();
    }

    /** 构建最多八千字符的工单建议冻结上下文。 */
    private String suggestionContext(String message, List<String> context) {
        String value = String.join("\n", context) + "\n用户当前问题：" + message.trim();
        return value.length() <= MAXIMUM_ANSWER_CHARACTERS ? value
                : value.substring(value.length() - MAXIMUM_ANSWER_CHARACTERS);
    }

    /** 将内部异常收敛为不泄露实现细节的客户端消息。 */
    private String safeMessage(RuntimeException exception) {
        return exception instanceof ApplicationException || exception instanceof ModelInvocationException
                ? exception.getMessage() : "服务暂时不可用，请稍后重试";
    }

    /** 判断客户端是否适合使用相同参数重试。 */
    private boolean retryable(String code, RuntimeException exception) {
        if (exception instanceof ModelInvocationException model) return model.retryable();
        return code.equals("RETRIEVAL_FAILED") || code.equals("CHAT_MODEL_UNAVAILABLE")
                || code.equals("DEPENDENCY_UNAVAILABLE");
    }

    /** 在模型首次产生内容时续租，丢失围栏则中止运行。 */
    private void renew(UUID ownerUserId, UUID conversationId, UUID runId, ChatExecutionControl control) {
        control.checkCancelled();
        if (!conversations.renew(ownerUserId, conversationId, runId, time.now())) {
            throw new ApplicationException(ErrorCode.CHAT_CONVERSATION_BUSY, "会话运行权已经失效");
        }
        control.checkCancelled();
    }

    /** 创建每次模型调用只记录一次首 Token 的租约续期回调。 */
    private Runnable firstTokenCallback(UUID ownerUserId, UUID conversationId,
                                        UUID runId, long modelStarted, ChatExecutionControl control) {
        AtomicBoolean first = new AtomicBoolean();
        return () -> {
            control.checkCancelled();
            if (first.compareAndSet(false, true)) {
                telemetry.recordDuration(Operation.MODEL_FIRST_TOKEN, elapsed(modelStarted), true);
            }
            renew(ownerUserId, conversationId, runId, control);
        };
    }

    /** 按自然段优先把完整安全答案切为不超过约三百字符的片段。 */
    private List<String> fragments(String answer) {
        List<String> values = new ArrayList<>();
        for (String paragraph : answer.split("(?<=\\n)")) {
            for (int start = 0; start < paragraph.length(); start += SAFE_FRAGMENT_CHARACTERS) {
                values.add(paragraph.substring(start,
                        Math.min(start + SAFE_FRAGMENT_CHARACTERS, paragraph.length())));
            }
        }
        return List.copyOf(values);
    }

    /** 将引用转换为 SSE data 中不含空字段的 Map。 */
    private Map<String, Object> citationData(Citation citation) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("citationId", citation.citationId());
        value.put("spaceId", citation.spaceId().toString());
        value.put("documentId", citation.documentId());
        value.put("documentTitle", citation.documentTitle());
        value.put("headingPath", citation.headingPath());
        value.put("sourceType", citation.sourceType());
        if (citation.sourceCaseId() != null) value.put("sourceCaseId", citation.sourceCaseId());
        return value;
    }

    /** 返回运行耗时毫秒。 */
    private long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    /** 保存路由完成后等待会话原子提交的内部结果。 */
    private record Outcome(String answer, String promptVersion, String resultStatus,
                           RetrievalStatus retrievalStatus, List<Citation> citations,
                           UUID suggestionId, String agentState) { }

    /** 保存同步校验后交给异步 SSE 执行阶段的不可变上下文。 */
    public record PreparedChat(ChatRequest request, UUID runId, BeginResult begin,
                               RetrievalAccessContext accessContext) { }
}
