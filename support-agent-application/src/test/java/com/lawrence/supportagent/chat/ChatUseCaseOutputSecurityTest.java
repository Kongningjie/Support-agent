package com.lawrence.supportagent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.chat.port.AgentAuditPort;
import com.lawrence.supportagent.chat.port.ConversationStorePort;
import com.lawrence.supportagent.chat.port.ConversationStorePort.BeginResult;
import com.lawrence.supportagent.chat.port.ConversationStorePort.BeginStatus;
import com.lawrence.supportagent.knowledge.DocumentContentPolicy;
import com.lawrence.supportagent.knowledge.ExactTermExtractor;
import com.lawrence.supportagent.model.ChatModelPort;
import com.lawrence.supportagent.model.ChatModelPort.ModelAnswer;
import com.lawrence.supportagent.model.ModelInvocationSecurity;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort;
import com.lawrence.supportagent.retrieval.RetrievalService;
import com.lawrence.supportagent.security.DeterministicPromptSecurityPolicy;
import com.lawrence.supportagent.security.LlmSecuritySettings;
import com.lawrence.supportagent.ticket.TicketQueryUseCase;
import com.lawrence.supportagent.user.UserRole;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 验证输出失败正文不可见、单次完整重生成和随机标记立即拒绝。 */
class ChatUseCaseOutputSecurityTest {
    private static final AuthenticatedUser ACTOR = new AuthenticatedUser(
            UUID.fromString("20000000-0000-0000-0000-000000000001"), "tester", UserRole.USER);

    /** 第一次空输出只触发完整重生成，客户端只能收到第二次通过的正文。 */
    @Test
    void shouldRegenerateOnceWithoutExposingFirstBody() {
        ChatModelPort model = mock(ChatModelPort.class);
        when(model.greeting(any(), any(), any(), any(ModelInvocationSecurity.class)))
                .thenReturn(new ModelAnswer("", "greeting-v1"))
                .thenReturn(new ModelAnswer("你好，请问需要什么技术支持？", "greeting-v1"));
        RecordingSink sink = new RecordingSink();

        useCase(preparedStore(), model).stream(request(), sink);

        assertThat(deltaTexts(sink)).containsExactly("你好，请问需要什么技术支持？");
        ArgumentCaptor<ModelInvocationSecurity> invocations =
                ArgumentCaptor.forClass(ModelInvocationSecurity.class);
        verify(model, times(2)).greeting(any(), any(), any(), invocations.capture());
        assertThat(invocations.getAllValues().get(0).canary())
                .isNotEqualTo(invocations.getAllValues().get(1).canary());
        assertThat(invocations.getAllValues().get(1).feedbackRules())
                .containsExactly("OUTPUT_LENGTH_INVALID");
    }

    /** 第二次仍不合格时只发送 error，不能发送任何答案片段或提交半轮会话。 */
    @Test
    void shouldReturnOnlyErrorWhenSecondOutputStillFails() {
        ConversationStorePort store = preparedStore();
        ChatModelPort model = mock(ChatModelPort.class);
        when(model.greeting(any(), any(), any(), any(ModelInvocationSecurity.class)))
                .thenReturn(new ModelAnswer("", "greeting-v1"));
        RecordingSink sink = new RecordingSink();

        useCase(store, model).stream(request(), sink);

        assertThat(deltaTexts(sink)).isEmpty();
        assertThat(sink.events).filteredOn(event -> "error".equals(event.eventType()))
                .singleElement().satisfies(event -> assertThat(event.data().get("code"))
                        .isEqualTo("CHAT_ANSWER_VALIDATION_FAILED"));
        verify(model, times(2)).greeting(any(), any(), any(), any(ModelInvocationSecurity.class));
        verify(store, never()).complete(any(), any(), any(), any(), any(), any());
    }

    /** 输出复述本次随机标记时必须立即拒绝，且不能执行第二次模型调用。 */
    @Test
    void shouldRejectCanaryLeakWithoutRegeneration() {
        ConversationStorePort store = preparedStore();
        ChatModelPort model = mock(ChatModelPort.class);
        when(model.greeting(any(), any(), any(), any(ModelInvocationSecurity.class)))
                .thenAnswer(invocation -> new ModelAnswer(
                        "泄漏 " + invocation.<ModelInvocationSecurity>getArgument(3).canary(),
                        "greeting-v1"));
        RecordingSink sink = new RecordingSink();

        useCase(store, model).stream(request(), sink);

        assertThat(deltaTexts(sink)).isEmpty();
        assertThat(sink.events).filteredOn(event -> "error".equals(event.eventType())).hasSize(1);
        verify(model).greeting(any(), any(), any(), any(ModelInvocationSecurity.class));
        verify(store, never()).complete(any(), any(), any(), any(), any(), any());
    }

    /** 创建固定进入问候分支且启用阶段 16 默认安全策略的聊天用例。 */
    private ChatUseCase useCase(ConversationStorePort store, ChatModelPort model) {
        AnswerValidator validator = new AnswerValidator(
                new ExactTermExtractor(), new DocumentContentPolicy());
        return new ChatUseCase(new IntentRecognitionService(
                (message, turns) -> new IntentDecision(ChatIntent.GREETING, 1,
                        message, null, "TEST"), 0.70), mock(RetrievalService.class), model,
                mock(TicketQueryUseCase.class), store, mock(AgentAuditPort.class), validator,
                UUID::randomUUID, () -> Instant.parse("2026-09-21T00:00:00Z"),
                "chat", "embedding", "rerank", 0.35, OptimizationTelemetryPort.noOp(),
                Duration.ofHours(24), null, null, new DeterministicPromptSecurityPolicy(
                new LlmSecuritySettings(true, true, true)));
    }

    /** 创建已取得运行租约的测试存储。 */
    private ConversationStorePort preparedStore() {
        ConversationStorePort store = mock(ConversationStorePort.class);
        when(store.begin(any(), any(), any(), any(), any(), any(), any())).thenReturn(
                new BeginResult(BeginStatus.ACQUIRED, UUID.randomUUID(), 0, null, null));
        when(store.recentContext(any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        AtomicLong sequence = new AtomicLong();
        when(store.nextSequence(any(), any(), any())).thenAnswer(ignored -> sequence.incrementAndGet());
        when(store.complete(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(3));
        return store;
    }

    /** 创建稳定的问候请求。 */
    private ChatRequest request() {
        return new ChatRequest(ACTOR, null, UUID.randomUUID(), "你好", null);
    }

    /** 提取客户端实际看见的答案片段。 */
    private List<String> deltaTexts(RecordingSink sink) {
        return sink.events.stream().filter(event -> "answer.delta".equals(event.eventType()))
                .map(event -> event.data().get("text").toString()).toList();
    }

    /** 收集 SSE 事件以验证失败正文不会外发。 */
    private static final class RecordingSink implements ChatEventSink {
        private final List<ChatEvent> events = new ArrayList<>();

        /** {@inheritDoc} */
        @Override public void send(ChatEvent event) { events.add(event); }

        /** {@inheritDoc} */
        @Override public void heartbeat() { }

        /** {@inheritDoc} */
        @Override public void complete() { }
    }
}
