package com.lawrence.supportagent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.chat.port.AgentAuditPort;
import com.lawrence.supportagent.chat.port.ConversationStorePort;
import com.lawrence.supportagent.chat.port.ConversationStorePort.BeginResult;
import com.lawrence.supportagent.chat.port.ConversationStorePort.BeginStatus;
import com.lawrence.supportagent.model.ChatModelPort;
import com.lawrence.supportagent.model.IntentRecognitionPort;
import com.lawrence.supportagent.retrieval.RetrievalService;
import com.lawrence.supportagent.ticket.TicketQueryUseCase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 验证各路由共享的 SSE 顺序和成功提交边界。 */
class ChatUseCaseTest {
    /** 会话版本等开始条件必须在接口创建 SSE 响应前同步失败。 */
    @Test
    void shouldRejectInvalidBeginDuringPreparation() {
        ConversationStorePort store = mock(ConversationStorePort.class);
        when(store.begin(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("版本冲突"));
        ChatUseCase useCase = useCase(store);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> useCase.prepare(
                new ChatRequest(UUID.randomUUID(), UUID.randomUUID(), "问题", 2L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("版本冲突");
    }

    /** 越界固定回答不应执行模型或检索，且完成事件必须位于正文之后。 */
    @Test
    void shouldEmitFixedBranchInStableOrder() {
        ConversationStorePort store = mock(ConversationStorePort.class);
        UUID conversationId = UUID.randomUUID();
        when(store.begin(any(), any(), any(), any(), any(), any())).thenReturn(
                new BeginResult(BeginStatus.ACQUIRED, conversationId, 0, null, null));
        when(store.recentContext(any(), anyInt(), anyInt())).thenReturn(List.of());
        AtomicLong sequence = new AtomicLong();
        when(store.nextSequence(any(), any())).thenAnswer(ignored -> sequence.incrementAndGet());
        when(store.complete(any(), any(), any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(2));
        ChatUseCase useCase = useCase(store);
        RecordingSink sink = new RecordingSink();
        useCase.stream(new ChatRequest(null, UUID.randomUUID(), "告诉我股票行情", null), sink);
        assertThat(sink.events).extracting(ChatEvent::eventType).containsExactly(
                "conversation.started", "answer.started", "answer.delta", "answer.completed");
        assertThat(sink.events).extracting(ChatEvent::sequence).containsExactly(1L, 2L, 3L, 4L);
        assertThat(sink.completed).isTrue();
    }

    /** 创建不允许实际模型调用的聊天用例。 */
    private ChatUseCase useCase(ConversationStorePort store) {
        IntentRecognitionPort intentModel = (message, turns) -> { throw new AssertionError("不应调用意图模型"); };
        return new ChatUseCase(new IntentRecognitionService(intentModel, 0.70),
                mock(RetrievalService.class), mock(ChatModelPort.class), mock(TicketQueryUseCase.class),
                store, mock(AgentAuditPort.class), mock(AnswerValidator.class), UUID::randomUUID,
                () -> Instant.parse("2026-09-08T00:00:00Z"), "chat", "embedding", "rerank", 0.35);
    }

    /** 收集应用层已经安全构造的测试事件。 */
    private static final class RecordingSink implements ChatEventSink {
        private final List<ChatEvent> events = new ArrayList<>();
        private boolean completed;
        /** {@inheritDoc} */ @Override public void send(ChatEvent event) { events.add(event); }
        /** {@inheritDoc} */ @Override public void heartbeat() { }
        /** {@inheritDoc} */ @Override public void complete() { completed = true; }
    }
}
