package com.lawrence.supportagent.chat;

import com.lawrence.supportagent.chat.port.AgentAuditPort;
import com.lawrence.supportagent.chat.port.ConversationStorePort;
import com.lawrence.supportagent.chat.port.ConversationStorePort.BeginResult;
import com.lawrence.supportagent.chat.port.ConversationStorePort.Citation;
import com.lawrence.supportagent.chat.port.ConversationStorePort.CompletedTurn;
import com.lawrence.supportagent.model.ChatModelPort;
import com.lawrence.supportagent.model.ChatModelPort.ModelAnswer;
import com.lawrence.supportagent.model.ModelInvocationException;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort.Operation;
import com.lawrence.supportagent.retrieval.RetrievalEvidence;
import com.lawrence.supportagent.retrieval.RetrievalResult;
import com.lawrence.supportagent.retrieval.RetrievalStatus;
import com.lawrence.supportagent.retrieval.RetrievalService;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** 编排意图、检索、受控模型、确定性校验、会话提交和 SSE 事件。 */
public class ChatUseCase {
    private static final int MODEL_CONTEXT_TURNS = 6;
    private static final int MODEL_CONTEXT_CHARACTERS = 12_000;
    private static final int MAXIMUM_ANSWER_CHARACTERS = 8_000;
    private static final int SAFE_FRAGMENT_CHARACTERS = 300;
    private static final Duration SUGGESTION_TTL = Duration.ofHours(24);
    private static final String NO_KNOWLEDGE = "当前知识库中没有找到足够可靠的依据，因此我不能给出确定的处理步骤。你可以确认创建一个技术支持工单，由人工继续处理。";
    private static final String OUT_OF_SCOPE = "我只能协助企业内部技术支持、知识问答和工单查询。请提供相关技术问题或工单编号。";
    private static final String TICKET_REQUIRED = "请提供格式为 T 加 12 位数字的工单编号。";
    private final IntentRecognitionService intents;
    private final RetrievalService retrieval;
    private final ChatModelPort chatModel;
    private final TicketQueryUseCase tickets;
    private final ConversationStorePort conversations;
    private final AgentAuditPort audits;
    private final AnswerValidator validator;
    private final UuidGenerator ids;
    private final TimeProvider time;
    private final String chatModelName;
    private final String embeddingModelName;
    private final String rerankModelName;
    private final double groundedThreshold;
    private final OptimizationTelemetryPort telemetry;

    /** 创建不向 Agent 下放检索路由或写权限的聊天用例。 */
    public ChatUseCase(IntentRecognitionService intents, RetrievalService retrieval,
                       ChatModelPort chatModel, TicketQueryUseCase tickets,
                       ConversationStorePort conversations, AgentAuditPort audits,
                       AnswerValidator validator, UuidGenerator ids, TimeProvider time,
                       String chatModelName, String embeddingModelName,
                       String rerankModelName, double groundedThreshold) {
        this(intents, retrieval, chatModel, tickets, conversations, audits, validator, ids, time,
                chatModelName, embeddingModelName, rerankModelName, groundedThreshold,
                OptimizationTelemetryPort.noOp());
    }

    /** 创建带二期低基数耗时遥测的聊天用例。 */
    public ChatUseCase(IntentRecognitionService intents, RetrievalService retrieval,
                       ChatModelPort chatModel, TicketQueryUseCase tickets,
                       ConversationStorePort conversations, AgentAuditPort audits,
                       AnswerValidator validator, UuidGenerator ids, TimeProvider time,
                       String chatModelName, String embeddingModelName,
                       String rerankModelName, double groundedThreshold,
                       OptimizationTelemetryPort telemetry) {
        this.intents = intents;
        this.retrieval = retrieval;
        this.chatModel = chatModel;
        this.tickets = tickets;
        this.conversations = conversations;
        this.audits = audits;
        this.validator = validator;
        this.ids = ids;
        this.time = time;
        this.chatModelName = chatModelName;
        this.embeddingModelName = embeddingModelName;
        this.rerankModelName = rerankModelName;
        this.groundedThreshold = groundedThreshold;
        this.telemetry = telemetry == null ? OptimizationTelemetryPort.noOp() : telemetry;
    }

    /**
     * 在建立 SSE 响应前同步校验会话版本、消息幂等键和运行租约。
     *
     * @param request 已通过接口参数校验的聊天请求
     * @return 已取得运行权或已命中重放的准备结果
     */
    public PreparedChat prepare(ChatRequest request) {
        UUID runId = ids.generate();
        BeginResult begin = conversations.begin(request.conversationId(), request.clientMessageId(),
                request.message(), request.expectedConversationVersion(), runId, time.now());
        return new PreparedChat(request, runId, begin);
    }

    /** 执行一次完整聊天运行，失败时不写半轮会话。 */
    public void stream(ChatRequest request, ChatEventSink sink) {
        stream(prepare(request), sink);
    }

    /**
     * 执行已完成同步准备的聊天运行。
     *
     * @param prepared 同步准备结果
     * @param sink SSE 事件接收端
     */
    public void stream(PreparedChat prepared, ChatEventSink sink) {
        long started = System.nanoTime();
        ChatRequest request = prepared.request();
        UUID runId = prepared.runId();
        BeginResult begin = prepared.begin();
        if (begin.status() == ConversationStorePort.BeginStatus.REPLAY) {
            replay(begin, sink);
            telemetry.recordDuration(Operation.CHAT_REQUEST, elapsed(started), true);
            return;
        }
        boolean committed = false;
        String completedPromptVersion = "unknown";
        try {
            if (begin.interruptedRunId() != null) {
                audits.fail(begin.interruptedRunId(), "INTERRUPTED", "CHAT_RUN_LEASE_EXPIRED", 0, time.now());
            }
            audits.start(runId, begin.conversationId(), request.clientMessageId(), time.now());
            emit(sink, begin.conversationId(), runId, "conversation.started",
                    Map.of("conversationVersion", begin.version(), "replayed", false));
            List<String> context = conversations.recentContext(begin.conversationId(),
                    MODEL_CONTEXT_TURNS, MODEL_CONTEXT_CHARACTERS);
            IntentDecision decision = intents.recognize(request.message(), context);
            audits.recordIntent(runId, decision);
            Outcome outcome = route(begin.conversationId(), runId, request.message(), context,
                    decision, sink);
            CompletedTurn pending = new CompletedTurn(ids.generate(), request.clientMessageId(), runId,
                    request.message().trim(), decision.standaloneQuery(), decision.intent(), outcome.answer,
                    outcome.retrievalStatus, outcome.citations, outcome.suggestionId,
                    suggestionContext(request.message(), context), outcome.resultStatus,
                    time.now(), begin.version() + 1);
            emitAnswerBody(sink, begin.conversationId(), runId, pending, started);
            Long suggestionSequence = pending.suggestionId() == null ? null
                    : conversations.nextSequence(begin.conversationId(), runId);
            long completedSequence = conversations.nextSequence(begin.conversationId(), runId);
            CompletedTurn completed = conversations.complete(begin.conversationId(), runId, pending,
                    outcome.agentState, time.now());
            committed = true;
            completedPromptVersion = outcome.promptVersion;
            emitAnswerCompleted(sink, begin.conversationId(), runId, completed,
                    suggestionSequence, completedSequence);
            telemetry.recordDuration(Operation.ANSWER_COMPLETE, elapsed(started), true);
            audits.succeed(runId, outcome.promptVersion, chatModelName, embeddingModelName,
                    rerankModelName, elapsed(started), time.now());
            sink.complete();
            telemetry.recordDuration(Operation.CHAT_REQUEST, elapsed(started), true);
        } catch (RuntimeException exception) {
            if (committed) {
                audits.succeed(runId, completedPromptVersion, chatModelName, embeddingModelName,
                        rerankModelName, elapsed(started), time.now());
                telemetry.recordDuration(Operation.CHAT_REQUEST, elapsed(started), true);
                return;
            }
            String code = exception instanceof ApplicationException application
                    ? application.errorCode().name() : exception instanceof ModelInvocationException model
                    ? model.errorCode() : "COMMON_INTERNAL_ERROR";
            try {
                emit(sink, begin.conversationId(), runId, "error", Map.of(
                        "code", code, "message", safeMessage(exception),
                        "retryable", retryable(code, exception)));
                sink.complete();
            } catch (RuntimeException ignored) {
                // 客户端已经断开时只执行失败清理。
            }
            conversations.fail(begin.conversationId(), runId, time.now());
            audits.fail(runId, "FAILED", code, elapsed(started), time.now());
            telemetry.recordDuration(Operation.CHAT_REQUEST, elapsed(started), false);
        }
    }

    /** 按应用层确定的意图执行唯一允许的分支。 */
    private Outcome route(UUID conversationId, UUID runId, String message, List<String> context,
                          IntentDecision decision, ChatEventSink sink) {
        return switch (decision.intent()) {
            case OUT_OF_SCOPE -> fixed(OUT_OF_SCOPE, "OUT_OF_SCOPE");
            case GREETING -> greeting(conversationId, runId, message, context);
            case TICKET_QUERY -> ticket(conversationId, runId, message, context, decision);
            case SUPPORT_QUERY -> support(conversationId, runId, message, context, decision, sink);
        };
    }

    /** 调用问候模型并记录真实首 Token 回调耗时。 */
    private Outcome greeting(UUID conversationId, UUID runId, String message, List<String> context) {
        long modelStarted = System.nanoTime();
        ModelAnswer answer = chatModel.greeting(message, context,
                firstTokenCallback(conversationId, runId, modelStarted));
        return fromModel(answer, "GREETING", null, List.of(), null, null);
    }

    /** 执行仅允许一次指定工单读取的 AgentScope 分支。 */
    private Outcome ticket(UUID conversationId, UUID runId, String message, List<String> context,
                           IntentDecision decision) {
        if (decision.ticketNo() == null) return fixed(TICKET_REQUIRED, "TICKET_NUMBER_REQUIRED");
        try {
            TicketDetails ticket = tickets.get(decision.ticketNo());
            ModelAnswer answer = chatModel.ticketAnswer(message, decision.ticketNo(), ticket, context,
                    () -> renew(conversationId, runId));
            return fromModel(answer, "TICKET_FOUND", null, List.of(), null,
                    answer.serializedAgentState());
        } catch (ApplicationException exception) {
            if (exception.errorCode() == ErrorCode.TICKET_NOT_FOUND) {
                return fixed("未找到工单 " + decision.ticketNo() + "，请核对编号。",
                        "TICKET_NOT_FOUND");
            }
            throw exception;
        }
    }

    /** 执行完整 RAG，并严格区分无知识与检索技术故障。 */
    private Outcome support(UUID conversationId, UUID runId, String message, List<String> context,
                            IntentDecision decision, ChatEventSink sink) {
        emit(sink, conversationId, runId, "retrieval.started", Map.of("mode", "HYBRID"));
        RetrievalResult result = retrieval.retrieve(decision.standaloneQuery());
        audits.recordRetrieval(runId, decision.standaloneQuery(), result, groundedThreshold, time.now());
        Map<String, Object> completed = new LinkedHashMap<>();
        completed.put("status", result.status().name());
        completed.put("bm25Status", result.bm25Status().name());
        completed.put("vectorStatus", result.vectorStatus().name());
        completed.put("rerankStatus", result.rerankStatus().name());
        completed.put("selectedCount", result.evidence().size());
        completed.put("durationMs", result.durationMs());
        emit(sink, conversationId, runId, "retrieval.completed", completed);
        if (result.status() == RetrievalStatus.RETRIEVAL_FAILED) {
            throw new ApplicationException(ErrorCode.RETRIEVAL_FAILED, "知识检索暂时不可用");
        }
        if (result.status() == RetrievalStatus.NO_RELIABLE_KNOWLEDGE) {
            return new Outcome(NO_KNOWLEDGE, "no-knowledge-v1", "NO_RELIABLE_KNOWLEDGE",
                    RetrievalStatus.NO_RELIABLE_KNOWLEDGE, List.of(), ids.generate(), null);
        }
        List<Citation> citations = citations(result.evidence());
        long modelStarted = System.nanoTime();
        ModelAnswer answer = chatModel.groundedAnswer(message, context, result.evidence(), null,
                firstTokenCallback(conversationId, runId, modelStarted));
        answer = withDisclosure(answer, result.evidence());
        List<String> failures = validator.validate(answer.text(), result.evidence());
        if (!failures.isEmpty()) {
            modelStarted = System.nanoTime();
            answer = chatModel.groundedAnswer(message, context, result.evidence(),
                    String.join(",", failures), firstTokenCallback(conversationId, runId, modelStarted));
            answer = withDisclosure(answer, result.evidence());
            if (!validator.validate(answer.text(), result.evidence()).isEmpty()) {
                throw new ApplicationException(ErrorCode.CHAT_ANSWER_VALIDATION_FAILED,
                        "模型答案未通过引用与安全校验");
            }
        }
        return fromModel(answer, "GROUNDED", RetrievalStatus.GROUNDED, citations, null, null);
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

    /** 在落库前发送安全正文；客户端发送失败时不提交会话轮次。 */
    private void emitAnswerBody(ChatEventSink sink, UUID conversationId, UUID runId,
                                CompletedTurn turn, long requestStarted) {
        emit(sink, conversationId, runId, "answer.started", Map.of("contentType", "text/markdown"));
        boolean firstFragment = true;
        for (String fragment : fragments(turn.answer())) {
            emit(sink, conversationId, runId, "answer.delta", Map.of("text", fragment));
            if (firstFragment) {
                telemetry.recordDuration(Operation.SAFE_FIRST_DELTA, elapsed(requestStarted), true);
                firstFragment = false;
            }
        }
        for (Citation citation : turn.citations()) {
            emit(sink, conversationId, runId, "citation", citationData(citation));
        }
    }

    /** 在原子提交成功后发送带有新版本的完成事件。 */
    private void emitAnswerCompleted(ChatEventSink sink, UUID conversationId, UUID runId,
                                     CompletedTurn turn, Long suggestionSequence,
                                     long completedSequence) {
        if (turn.suggestionId() != null) {
            emitPrepared(sink, conversationId, runId, suggestionSequence, "ticket.suggested", Map.of(
                    "suggestionId", turn.suggestionId().toString(),
                    "expiresAt", turn.completedAt().plus(SUGGESTION_TTL).toString()));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answer", turn.answer());
        data.put("citations", turn.citations().stream().map(this::citationData).toList());
        data.put("conversationVersion", turn.conversationVersion());
        data.put("resultStatus", turn.resultStatus());
        emitPrepared(sink, conversationId, runId, completedSequence, "answer.completed", data);
    }

    /** 重发成功消息而不执行模型、检索或增加会话版本。 */
    private void replay(BeginResult begin, ChatEventSink sink) {
        CompletedTurn turn = begin.replayTurn();
        emitReplay(sink, begin.conversationId(), turn.runId(), "conversation.started",
                Map.of("conversationVersion", begin.version(), "replayed", true));
        emitReplayAnswer(sink, begin.conversationId(), turn.runId(), turn);
        sink.complete();
    }

    /** 使用新事件编号和新序号重放既有安全内容。 */
    private void emitReplayAnswer(ChatEventSink sink, UUID conversationId, UUID runId,
                                  CompletedTurn turn) {
        emitReplay(sink, conversationId, runId, "answer.started", Map.of("contentType", "text/markdown"));
        for (String fragment : fragments(turn.answer())) {
            emitReplay(sink, conversationId, runId, "answer.delta", Map.of("text", fragment));
        }
        for (Citation citation : turn.citations()) {
            emitReplay(sink, conversationId, runId, "citation", citationData(citation));
        }
        if (turn.suggestionId() != null) {
            emitReplay(sink, conversationId, runId, "ticket.suggested", Map.of(
                    "suggestionId", turn.suggestionId().toString(),
                    "expiresAt", turn.completedAt().plus(SUGGESTION_TTL).toString()));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answer", turn.answer());
        data.put("citations", turn.citations().stream().map(this::citationData).toList());
        data.put("conversationVersion", turn.conversationVersion());
        data.put("resultStatus", turn.resultStatus());
        emitReplay(sink, conversationId, runId, "answer.completed", data);
    }

    /** 为最终证据按顺序分配 S1 开始的临时引用。 */
    private List<Citation> citations(List<RetrievalEvidence> evidence) {
        List<Citation> values = new ArrayList<>();
        for (int index = 0; index < evidence.size(); index++) {
            RetrievalEvidence item = evidence.get(index);
            values.add(new Citation("S" + (index + 1), Long.toString(item.sourceId()), item.title(),
                    item.headingPath(), item.sourceType(), "RESOLVED_CASE".equals(item.sourceType())
                    ? Long.toString(item.sourceId()) : null));
        }
        return List.copyOf(values);
    }

    /** 构造固定安全回答结果。 */
    private Outcome fixed(String answer, String resultStatus) {
        return new Outcome(answer, "fixed-v1", resultStatus, null, List.of(), null, null);
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
    private void emit(ChatEventSink sink, UUID conversationId, UUID runId,
                      String eventType, Map<String, Object> data) {
        long sequence = conversations.nextSequence(conversationId, runId);
        sink.send(new ChatEvent(ids.generate(), eventType, runId, conversationId,
                sequence, time.now(), Map.copyOf(data)));
    }

    /** 发送不要求存在活动运行租约的重放事件。 */
    private void emitReplay(ChatEventSink sink, UUID conversationId, UUID runId,
                            String eventType, Map<String, Object> data) {
        long sequence = conversations.nextReplaySequence(conversationId);
        sink.send(new ChatEvent(ids.generate(), eventType, runId, conversationId,
                sequence, time.now(), Map.copyOf(data)));
    }

    /** 使用提交前预留的序号发送提交后的终结事件。 */
    private void emitPrepared(ChatEventSink sink, UUID conversationId, UUID runId, long sequence,
                              String eventType, Map<String, Object> data) {
        sink.send(new ChatEvent(ids.generate(), eventType, runId, conversationId,
                sequence, time.now(), Map.copyOf(data)));
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
    private void renew(UUID conversationId, UUID runId) {
        if (!conversations.renew(conversationId, runId, time.now())) {
            throw new ApplicationException(ErrorCode.CHAT_CONVERSATION_BUSY, "会话运行权已经失效");
        }
    }

    /** 创建每次模型调用只记录一次首 Token 的租约续期回调。 */
    private Runnable firstTokenCallback(UUID conversationId, UUID runId, long modelStarted) {
        AtomicBoolean first = new AtomicBoolean();
        return () -> {
            if (first.compareAndSet(false, true)) {
                telemetry.recordDuration(Operation.MODEL_FIRST_TOKEN, elapsed(modelStarted), true);
            }
            renew(conversationId, runId);
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
    public record PreparedChat(ChatRequest request, UUID runId, BeginResult begin) { }
}
