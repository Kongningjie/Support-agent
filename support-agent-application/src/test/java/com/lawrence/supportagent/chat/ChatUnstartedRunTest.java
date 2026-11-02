package com.lawrence.supportagent.chat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.chat.ChatExecutionControl.CancellationReason;
import com.lawrence.supportagent.chat.port.AgentAuditPort;
import com.lawrence.supportagent.chat.port.ConversationStorePort;
import com.lawrence.supportagent.chat.port.ConversationStorePort.BeginResult;
import com.lawrence.supportagent.chat.port.ConversationStorePort.BeginStatus;
import com.lawrence.supportagent.model.ChatModelPort;
import com.lawrence.supportagent.retrieval.RetrievalService;
import com.lawrence.supportagent.ticket.TicketQueryUseCase;
import com.lawrence.supportagent.user.UserRole;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 验证调度失败或开始前断连只终结本次已取得的运行，不触碰重放原结果。 */
class ChatUnstartedRunTest {
    private final ConversationStorePort store = mock(ConversationStorePort.class);
    private final AgentAuditPort audits = mock(AgentAuditPort.class);
    private final Instant now = Instant.parse("2026-11-02T00:00:00Z");
    private final ChatUseCase useCase = new ChatUseCase(mock(IntentRecognitionService.class),
            mock(RetrievalService.class), mock(ChatModelPort.class), mock(TicketQueryUseCase.class),
            store, audits, mock(AnswerValidator.class), UUID::randomUUID, () -> now,
            "chat", "embedding", "rerank", 0.35);

    /** 新运行未启动也要形成失败审计，不提交成功轮次。 */
    @Test
    void shouldReleaseAndAuditUnstartedRun() {
        ChatUseCase.PreparedChat prepared = prepared(BeginStatus.ACQUIRED);
        useCase.failBeforeStart(prepared, CancellationReason.CHAT_RESPONSE_TIMEOUT);
        verify(store).fail(prepared.request().actor().userId(), prepared.begin().conversationId(),
                prepared.runId(), now);
        verify(audits).start(prepared.runId(), prepared.request().actor().userId(),
                prepared.begin().conversationId(), prepared.request().clientMessageId(), now);
        verify(audits).fail(prepared.runId(), "FAILED", "CHAT_RESPONSE_TIMEOUT", 0, now);
        verify(store, never()).complete(any(), any(), any(), any(), any(), any());
    }

    /** 已完成重放未取得新运行，断连或调度失败均不得改动原结果。 */
    @Test
    void shouldLeaveReplayUntouched() {
        useCase.failBeforeStart(prepared(BeginStatus.REPLAY), CancellationReason.CHAT_CLIENT_DISCONNECTED);
        verifyNoInteractions(store, audits);
    }

    /** Redis 清理失败仍独立记录审计，不掩盖清理失败。 */
    @Test
    void shouldAuditEvenWhenReleaseFails() {
        ChatUseCase.PreparedChat prepared = prepared(BeginStatus.ACQUIRED);
        doThrow(new IllegalStateException("synthetic redis failure")).when(store)
                .fail(any(), any(), any(), any());
        assertThatThrownBy(() -> useCase.failBeforeStart(prepared, null))
                .isInstanceOf(IllegalStateException.class).hasMessage("synthetic redis failure");
        verify(audits).fail(prepared.runId(), "FAILED", "COMMON_INTERNAL_ERROR", 0, now);
    }

    /** 审计创建失败也不能阻止先释放运行租约。 */
    @Test
    void shouldReleaseBeforeAuditFailure() {
        ChatUseCase.PreparedChat prepared = prepared(BeginStatus.ACQUIRED);
        doThrow(new IllegalStateException("synthetic audit failure")).when(audits)
                .start(any(), any(), any(), any(), any());
        assertThatThrownBy(() -> useCase.failBeforeStart(prepared, null))
                .isInstanceOf(IllegalStateException.class).hasMessage("synthetic audit failure");
        verify(store).fail(prepared.request().actor().userId(), prepared.begin().conversationId(),
                prepared.runId(), now);
        verify(audits).fail(prepared.runId(), "FAILED", "COMMON_INTERNAL_ERROR", 0, now);
    }

    /** 构造已准备执行上下文；不运行模型、安全路由或检索。 */
    private ChatUseCase.PreparedChat prepared(BeginStatus status) {
        AuthenticatedUser actor = new AuthenticatedUser(UUID.randomUUID(), "tester", UserRole.USER);
        UUID spaceId = UUID.randomUUID();
        ChatRequest request = new ChatRequest(actor, spaceId, null, UUID.randomUUID(), "技术问题", null);
        return new ChatUseCase.PreparedChat(request, UUID.randomUUID(),
                new BeginResult(status, UUID.randomUUID(), 0, spaceId, null, null), null);
    }
}
