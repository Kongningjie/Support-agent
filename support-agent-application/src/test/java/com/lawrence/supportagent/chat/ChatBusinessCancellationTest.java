package com.lawrence.supportagent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.chat.ChatExecutionControl.CancellationReason;
import com.lawrence.supportagent.chat.port.AgentAuditPort;
import com.lawrence.supportagent.chat.port.ConversationStorePort;
import com.lawrence.supportagent.chat.port.ConversationStorePort.BeginResult;
import com.lawrence.supportagent.chat.port.ConversationStorePort.BeginStatus;
import com.lawrence.supportagent.chat.port.ConversationStorePort.CompletedTurn;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpace;
import com.lawrence.supportagent.memory.UserMemoryCandidateService;
import com.lawrence.supportagent.model.ChatModelPort;
import com.lawrence.supportagent.model.ChatModelPort.ModelAnswer;
import com.lawrence.supportagent.model.ModelInvocationSecurity;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort;
import com.lawrence.supportagent.retrieval.RetrievalService;
import com.lawrence.supportagent.security.DeterministicPromptSecurityPolicy;
import com.lawrence.supportagent.security.LlmSecuritySettings;
import com.lawrence.supportagent.security.ModelOutputAction;
import com.lawrence.supportagent.security.ModelOutputAssessment;
import com.lawrence.supportagent.security.ModelOutputRule;
import com.lawrence.supportagent.security.ModelOutputSecurityService;
import com.lawrence.supportagent.ticket.TicketQueryUseCase;
import com.lawrence.supportagent.user.UserRole;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 验证业务边界取消、重生成阻断、独立清理和已提交结果保护。 */
class ChatBusinessCancellationTest {
    private final ConversationStorePort store = mock(ConversationStorePort.class);
    private final AgentAuditPort audits = mock(AgentAuditPort.class);
    private final ChatModelPort model = mock(ChatModelPort.class);
    private final IntentRecognitionService intents = mock(IntentRecognitionService.class);
    private final RetrievalService retrieval = mock(RetrievalService.class);
    private final TicketQueryUseCase tickets = mock(TicketQueryUseCase.class);
    private final ModelOutputSecurityService security = mock(ModelOutputSecurityService.class);
    private final UserMemoryCandidateService memories = mock(UserMemoryCandidateService.class);
    private final ChatExecutionControl control = new ChatExecutionControl();
    private final RecordingSink sink = new RecordingSink();
    private final Instant now = Instant.parse("2026-11-02T00:00:00Z");
    private ChatUseCase useCase;
    private ChatUseCase.PreparedChat prepared;

    /** 明确围栏拒绝保持原冲突错误语义，不发起核实或修改新运行。 */
    @Test void shouldPreserveExplicitCommitRejection() {
        when(store.complete(any(), any(), any(), any(), any(), any())).thenThrow(
                new com.lawrence.supportagent.sharedkernel.error.ApplicationException(
                        com.lawrence.supportagent.sharedkernel.error.ErrorCode.CHAT_CONVERSATION_BUSY, "运行权失效"));
        execute();
        verify(store, never()).resolveCommitOrFail(any(), any(), any(), any(), anyLong(), any());
        verify(store, never()).fail(any(), any(), any(), any());
        verify(audits).fail(any(), eq("FAILED"), eq("CHAT_CONVERSATION_BUSY"), anyLong(), any());
        assertThat(sink.events).filteredOn(event -> "error".equals(event.eventType()))
                .singleElement().satisfies(event -> assertThat(event.data().get("code")).isEqualTo("CHAT_CONVERSATION_BUSY"));
    }

    /** complete 明确成功期间断连仍保留提交并写成功审计，不发送完成或回滚。 */
    @Test void shouldKeepCommitThatWonBeforeCancellation() {
        when(store.complete(any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            cancel();
            return call.getArgument(3);
        });
        execute();
        verify(audits).succeed(any(), any(), any(), any(), any(), anyLong(), any());
        verify(store, never()).fail(any(), any(), any(), any());
        verify(store, never()).resolveCommitOrFail(any(), any(), any(), any(), anyLong(), any());
        assertThat(sink.events).noneMatch(event -> "answer.completed".equals(event.eventType()));
    }

    /** 提交响应丢失后核实为成功，只写成功审计且继续发送原结果。 */
    @Test void shouldResolveLostCommitResponseAsSuccess() {
        var begin = prepared.begin();
        prepared = new ChatUseCase.PreparedChat(prepared.request(), prepared.runId(),
                new BeginResult(begin.status(), begin.conversationId(), begin.version(), begin.spaceId(),
                        null, null, 7), prepared.accessContext());
        when(store.complete(any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            CompletedTurn saved = call.getArgument(3);
            when(store.resolveCommitOrFail(any(), any(), any(), any(), anyLong(), any()))
                    .thenReturn(new ConversationStorePort.CommitResolution(ConversationStorePort.CommitStatus.COMMITTED, saved));
            throw new IllegalStateException("synthetic response lost");
        });
        execute();
        verify(audits).succeed(any(), any(), any(), any(), any(), anyLong(), any());
        verify(store).resolveCommitOrFail(eq(prepared.request().actor().userId()), eq(begin.conversationId()),
                eq(prepared.request().clientMessageId()), eq(prepared.runId()), eq(7L), any());
        verify(audits, never()).fail(any(), any(), any(), anyLong(), any());
        verify(store, never()).fail(any(), any(), any(), any());
        assertThat(sink.events).anyMatch(event -> "answer.completed".equals(event.eventType()));
    }

    /** 核实明确失败后不再普通 fail，即使错误发送又发生取消也只能终结一次。 */
    @Test void shouldNotRepeatFailureAfterAtomicResolution() {
        when(store.complete(any(), any(), any(), any(), any(), any())).thenThrow(new IllegalStateException());
        when(store.resolveCommitOrFail(any(), any(), any(), any(), anyLong(), any())).thenReturn(
                new ConversationStorePort.CommitResolution(ConversationStorePort.CommitStatus.FAILED, null));
        sink.onSend = event -> { if ("error".equals(event.eventType())) { cancel(); throw new IllegalStateException(); } };
        execute();
        verify(audits).fail(any(), eq("FAILED"), eq("DEPENDENCY_UNAVAILABLE"), anyLong(), any());
        verify(store, never()).fail(any(), any(), any(), any());
        verify(audits, never()).succeed(any(), any(), any(), any(), any(), anyLong(), any());
    }

    /** 存储核实不可用不得伪造失败、成功或开始摘要/记忆。 */
    @Test void shouldLeaveUnavailableCommitOutcomeUnknown() {
        ConversationContextService contexts = mock(ConversationContextService.class);
        when(contexts.prepare(any(), any(), any())).thenReturn(new ConversationContext(List.of(), 0, 0, 0));
        useCase = createUseCase(contexts, memories);
        when(store.complete(any(), any(), any(), any(), any(), any())).thenThrow(new IllegalStateException());
        when(store.resolveCommitOrFail(any(), any(), any(), any(), anyLong(), any())).thenThrow(new IllegalStateException());
        execute();
        verify(store, never()).fail(any(), any(), any(), any());
        verify(audits, never()).fail(any(), any(), any(), anyLong(), any());
        verify(audits, never()).succeed(any(), any(), any(), any(), any(), anyLong(), any());
        verify(contexts, never()).afterSuccessfulTurn(any(), any());
        verifyNoInteractions(memories);
        assertThat(sink.events).noneMatch(event -> List.of("answer.completed", "error").contains(event.eventType()));
    }

    /** 核实已不拥有目标代际或运行时同样不能改写终态。 */
    @Test void shouldNotCleanUpUnownedRun() {
        when(store.complete(any(), any(), any(), any(), any(), any())).thenThrow(new IllegalStateException());
        when(store.resolveCommitOrFail(any(), any(), any(), any(), anyLong(), any())).thenReturn(
                new ConversationStorePort.CommitResolution(ConversationStorePort.CommitStatus.NOT_OWNED, null));
        execute();
        verify(store, never()).fail(any(), any(), any(), any());
        verify(audits, never()).fail(any(), any(), any(), anyLong(), any());
        assertThat(sink.events).noneMatch(event -> "answer.completed".equals(event.eventType()));
    }

    /** MySQL 成功审计抛异常不能抹掉 Redis 已保存轮次，也不重复写审计。 */
    @Test void shouldKeepSuccessWhenAuditUnavailable() {
        doThrow(new IllegalStateException()).when(audits).succeed(any(), any(), any(), any(), any(), anyLong(), any());
        execute();
        verify(audits).succeed(any(), any(), any(), any(), any(), anyLong(), any());
        verify(audits, never()).fail(any(), any(), any(), anyLong(), any());
        verify(store, never()).fail(any(), any(), any(), any());
        assertThat(sink.events).anyMatch(event -> "answer.completed".equals(event.eventType()));
    }

    /** 默认走问候分支，所有持久化与模型均为确定性隔离桩。 */
    @BeforeEach
    void setUp() {
        useCase = createUseCase(null, null);
        var actor = new AuthenticatedUser(UUID.randomUUID(), "cancellation-test", UserRole.USER);
        var request = new ChatRequest(actor, KnowledgeSpace.GLOBAL_SPACE_ID, null,
                UUID.randomUUID(), "你好", null);
        prepared = new ChatUseCase.PreparedChat(request, UUID.randomUUID(),
                new BeginResult(BeginStatus.ACQUIRED, UUID.randomUUID(), 0,
                        KnowledgeSpace.GLOBAL_SPACE_ID, null, null),
                new com.lawrence.supportagent.retrieval.RetrievalAccessContext(actor,
                        KnowledgeSpace.GLOBAL_SPACE_ID, Set.of(KnowledgeSpace.GLOBAL_SPACE_ID)));
        when(store.recentContext(any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(intents.recognize(any(), any(), same(control))).thenReturn(
                new IntentDecision(ChatIntent.GREETING, 1, "你好", null, "TEST"));
        when(security.newInvocation(any())).thenReturn(ModelInvocationSecurity.none());
        when(security.assess(any(), any(), any(), any(), any())).thenReturn(
                new ModelOutputAssessment(ModelOutputAction.PASS, List.of()));
        when(model.greeting(any(), any(), any(), any(), same(control)))
                .thenReturn(new ModelAnswer("安全回答".repeat(120), "test-v1"));
        when(store.complete(any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> call.getArgument(3));
    }

    /** 开始前取消仍终结已取得的运行，不加载历史或调用模型。 */
    @Test
    void shouldStopBeforeContextAndReleaseRun() {
        cancel();
        execute();
        verify(store, never()).recentContext(any(), any(), anyInt(), anyInt());
        verifyNoInteractions(intents, model, security);
        assertCancelled(CancellationReason.CHAT_CLIENT_DISCONNECTED);
    }

    /** 同步上下文读取期间断连，只等既有读取结束，不能继续识别意图。 */
    @Test
    void shouldStopAfterContextRead() {
        when(store.recentContext(any(), any(), anyInt(), anyInt())).thenAnswer(call -> {
            cancel();
            return List.of("历史内容");
        });
        execute();
        verifyNoInteractions(intents, model);
        assertCancelled(CancellationReason.CHAT_CLIENT_DISCONNECTED);
    }

    /** 同步摘要准备期间断连，已开始的工作允许结束，返回后必须停在上下文边界。 */
    @Test
    void shouldStopAfterSynchronousContextPreparation() {
        ConversationContextService contexts = mock(ConversationContextService.class);
        when(contexts.prepare(any(), any(), any())).thenAnswer(call -> {
            cancel();
            return new ConversationContext(List.of("摘要"), 1, 1, 1);
        });
        useCase = createUseCase(contexts, memories);
        execute();
        verifyNoInteractions(intents, model, memories);
        verify(contexts, never()).afterSuccessfulTurn(any(), any());
        assertCancelled(CancellationReason.CHAT_CLIENT_DISCONNECTED);
    }

    /** 工单未找到与断连同时发生，不再构造或校验未找到的固定兜底正文。 */
    @Test
    void shouldNotFallbackAfterTicketLookupCancellation() {
        when(intents.recognize(any(), any(), any())).thenReturn(
                new IntentDecision(ChatIntent.TICKET_QUERY, 1, "工单", "T123456789012", "TEST"));
        when(tickets.get(any(), any())).thenAnswer(call -> {
            cancel();
            throw new com.lawrence.supportagent.sharedkernel.error.ApplicationException(
                    com.lawrence.supportagent.sharedkernel.error.ErrorCode.TICKET_NOT_FOUND, "未找到工单");
        });
        execute();
        verifyNoInteractions(model, security);
        assertCancelled(CancellationReason.CHAT_CLIENT_DISCONNECTED);
    }

    /** 意图返回同时断连，不能路由或记录新的意图结果。 */
    @Test
    void shouldStopAfterIntent() {
        when(intents.recognize(any(), any(), any())).thenAnswer(call -> {
            cancel();
            return new IntentDecision(ChatIntent.GREETING, 1, "你好", null, "TEST");
        });
        execute();
        verify(audits, never()).recordIntent(any(), any());
        verifyNoInteractions(model, retrieval);
        assertCancelled(CancellationReason.CHAT_CLIENT_DISCONNECTED);
    }

    /** 检索同步调用返回时取消，禁止开始证据校验、模型生成或检索完成事件。 */
    @Test
    void shouldStopAfterRetrieval() {
        when(intents.recognize(any(), any(), any())).thenReturn(
                new IntentDecision(ChatIntent.SUPPORT_QUERY, 1, "技术问题", null, "TEST"));
        when(retrieval.retrieve(any(), any(com.lawrence.supportagent.retrieval.RetrievalAccessContext.class)))
                .thenAnswer(call -> { cancel(); return null; });
        execute();
        verifyNoInteractions(model, security);
        assertThat(sink.events).noneMatch(event -> "retrieval.completed".equals(event.eventType()));
        assertCancelled(CancellationReason.CHAT_CLIENT_DISCONNECTED);
    }

    /** 模型返回时已取消，不能启动完整输出校验或第二次生成。 */
    @Test
    void shouldStopBeforeOutputValidation() {
        when(model.greeting(any(), any(), any(), any(), any())).thenAnswer(call -> {
            cancel();
            return new ModelAnswer("未校验的答案", "test-v1");
        });
        execute();
        verify(security, never()).assess(any(), any(), any(), any(), any());
        verify(model).greeting(any(), any(), any(), any(), any());
        assertCancelled(CancellationReason.CHAT_CLIENT_DISCONNECTED);
    }

    /** 校验建议重生成但校验期间取消，禁止记录重生成或再调用模型。 */
    @Test
    void shouldNotRegenerateAfterValidationCancellation() {
        when(security.assess(any(), any(), any(), any(), any())).thenAnswer(call -> {
            control.cancel(CancellationReason.CHAT_RESPONSE_TIMEOUT);
            return new ModelOutputAssessment(ModelOutputAction.REGENERATE, List.of(ModelOutputRule.OUTPUT_LENGTH_INVALID));
        });
        execute();
        verify(model).greeting(any(), any(), any(), any(), any());
        verify(security, never()).recordRegeneration(any());
        assertCancelled(CancellationReason.CHAT_RESPONSE_TIMEOUT);
    }

    /** 第一片正文送达后断连，不发送下一片或完成事件，也不提交完整轮次。 */
    @Test
    void shouldStopBetweenBodyFragments() {
        sink.onSend = event -> { if ("answer.delta".equals(event.eventType())) cancel(); };
        execute();
        assertThat(sink.events).filteredOn(event -> "answer.delta".equals(event.eventType())).hasSize(1);
        assertCancelled(CancellationReason.CHAT_CLIENT_DISCONNECTED);
    }

    /** 最后一个预留序号返回时取消，紧邻 complete 的检查必须阻止提交。 */
    @Test
    void shouldCheckImmediatelyBeforeCommit() {
        var sequences = new java.util.concurrent.atomic.AtomicInteger();
        when(store.nextSequence(any(), any(), any())).thenAnswer(call -> {
            if (sequences.incrementAndGet() == 5) cancel();
            return (long) sequences.get();
        });
        execute();
        assertCancelled(CancellationReason.CHAT_CLIENT_DISCONNECTED);
    }

    /** 普通依赖异常与断连同时到达，以首次取消原因为准且不发送 error。 */
    @Test
    void shouldPreferCancellationToOrdinaryFailure() {
        when(model.greeting(any(), any(), any(), any(), any())).thenAnswer(call -> {
            control.cancel(CancellationReason.CHAT_CLIENT_TOO_SLOW);
            cancel();
            throw new IllegalStateException("synthetic failure");
        });
        execute();
        assertCancelled(CancellationReason.CHAT_CLIENT_TOO_SLOW);
    }

    /** 错误事件发送才触发断连，也必须使用取消原因且只清理一次。 */
    @Test
    void shouldPreferCancellationDuringErrorSend() {
        when(model.greeting(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException());
        sink.onSend = event -> {
            if ("error".equals(event.eventType())) { cancel(); throw new IllegalStateException(); }
        };
        execute();
        verify(audits).fail(eq(prepared.runId()), eq("FAILED"),
                eq("CHAT_CLIENT_DISCONNECTED"), anyLong(), eq(now));
        verify(store).fail(any(), any(), eq(prepared.runId()), eq(now));
    }

    /** Redis 清理异常不能阻断失败审计，也不能导致 error 发送或重复失败。 */
    @Test
    void shouldAuditWhenRedisCleanupFails() {
        doThrow(new IllegalStateException("synthetic redis failure")).when(store).fail(any(), any(), any(), any());
        cancel();
        execute();
        assertCancelled(CancellationReason.CHAT_CLIENT_DISCONNECTED);
    }

    /** 审计失败仍已独立尝试释放本次运行；finally 始终关闭发送端。 */
    @Test
    void shouldReleaseWhenAuditFails() {
        doThrow(new IllegalStateException("synthetic audit failure")).when(audits)
                .fail(any(), any(), any(), anyLong(), any());
        cancel();
        execute();
        assertCancelled(CancellationReason.CHAT_CLIENT_DISCONNECTED);
        assertThat(sink.closed).isTrue();
    }

    /** 明确提交成功后断连保留成功，跳过尚未开始的摘要和记忆工作。 */
    @Test
    void shouldPreserveCommittedSuccessAndSkipPostProcessing() {
        ConversationContextService contexts = mock(ConversationContextService.class);
        when(contexts.prepare(any(), any(), any())).thenReturn(new ConversationContext(List.of(), 0, 0, 0));
        sink.onSend = event -> { if ("answer.completed".equals(event.eventType())) cancel(); };
        useCase = createUseCase(contexts, memories);
        execute();
        verify(store).complete(any(), any(), any(), any(), any(), any());
        verify(audits).succeed(eq(prepared.runId()), eq("test-v1"), any(), any(), any(), anyLong(), any());
        verify(audits, never()).fail(any(), any(), any(), anyLong(), any());
        verify(store, never()).fail(any(), any(), any(), any());
        verifyNoInteractions(memories);
        verify(contexts, never()).afterSuccessfulTurn(any(), any());
    }

    /** 摘要后处理已开始才收到取消，允许其返回，但不能启动后续记忆候选任务。 */
    @Test
    void shouldSkipMemoryWhenCancelledDuringStartedSummaryPostProcessing() {
        ConversationContextService contexts = mock(ConversationContextService.class);
        when(contexts.prepare(any(), any(), any())).thenReturn(new ConversationContext(List.of(), 0, 0, 0));
        org.mockito.Mockito.doAnswer(call -> { cancel(); return null; })
                .when(contexts).afterSuccessfulTurn(any(), any());
        useCase = createUseCase(contexts, memories);
        execute();
        verify(contexts).afterSuccessfulTurn(any(), any());
        verifyNoInteractions(memories);
        verify(store, never()).fail(any(), any(), any(), any());
        verify(audits, never()).fail(any(), any(), any(), anyLong(), any());
    }

    /** 取消后迟到的首 Token 回调不能给已结束运行续租。 */
    @Test
    void shouldRejectLateTokenCallback() {
        AtomicReference<Runnable> token = new AtomicReference<>();
        when(model.greeting(any(), any(), any(), any(), any())).thenAnswer(call -> {
            token.set(call.getArgument(2));
            cancel();
            return new ModelAnswer("答案", "test-v1");
        });
        execute();
        assertThatThrownBy(() -> token.get().run()).isInstanceOf(ChatCancellationException.class);
        verify(store, never()).renew(any(), any(), any(), any());
    }

    /** 重放发送中取消不改写原成功审计，也不清理可能已经启动的新运行。 */
    @Test
    void shouldLeaveOriginalResultUntouchedWhenReplayDisconnects() {
        CompletedTurn turn = mock(CompletedTurn.class);
        when(turn.runId()).thenReturn(UUID.randomUUID());
        prepared = new ChatUseCase.PreparedChat(prepared.request(), prepared.runId(),
                new BeginResult(BeginStatus.REPLAY, prepared.begin().conversationId(), 1,
                        KnowledgeSpace.GLOBAL_SPACE_ID, turn, null), null);
        sink.onSend = event -> cancel();
        execute();
        verifyNoInteractions(audits, model, intents);
        verify(store, never()).fail(any(), any(), any(), any());
        assertThat(sink.closed).isTrue();
    }

    /** 使用同一请求级控制执行一次实际应用用例。 */
    private void execute() { useCase.stream(prepared, sink, control); }

    /** 首次取消原因必须保持不变。 */
    private void cancel() { control.cancel(CancellationReason.CHAT_CLIENT_DISCONNECTED); }

    /** 取消结果只清理指定所有者、会话和 runId，不允许正文完成、错误或成功写入。 */
    private void assertCancelled(CancellationReason reason) {
        verify(store).fail(prepared.request().actor().userId(), prepared.begin().conversationId(), prepared.runId(), now);
        verify(audits).fail(eq(prepared.runId()), eq("FAILED"), eq(reason.name()), anyLong(), eq(now));
        verify(store, never()).complete(any(), any(), any(), any(), any(), any());
        verify(audits, never()).succeed(any(), any(), any(), any(), any(), anyLong(), any());
        assertThat(sink.events).noneMatch(event -> List.of("error", "answer.completed").contains(event.eventType()));
    }

    /** 装配实际编排器，仅替换外部依赖；不调用真实模型或密钥。 */
    private ChatUseCase createUseCase(ConversationContextService contexts, UserMemoryCandidateService candidates) {
        return new ChatUseCase(intents, retrieval, model, tickets, store, audits,
                mock(AnswerValidator.class), UUID::randomUUID, () -> now, "chat", "embedding", "rerank", 0.35,
                OptimizationTelemetryPort.noOp(), Duration.ofHours(24), contexts, candidates,
                new DeterministicPromptSecurityPolicy(new LlmSecuritySettings(true, true, true)), security);
    }

    /** 模拟发送期间的生命周期通知，保留实际业务事件供断言。 */
    private static final class RecordingSink implements ChatEventSink {
        private final List<ChatEvent> events = new ArrayList<>();
        private java.util.function.Consumer<ChatEvent> onSend = event -> { };
        private boolean closed;
        /** {@inheritDoc} */
        @Override public void send(ChatEvent event) { events.add(event); onSend.accept(event); }
        /** {@inheritDoc} */
        @Override public void heartbeat() { }
        /** {@inheritDoc} */
        @Override public void complete() { closed = true; }
    }
}
