package com.lawrence.supportagent.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.chat.port.ConversationStorePort;
import com.lawrence.supportagent.chat.port.ConversationStorePort.SuggestionClaim;
import com.lawrence.supportagent.model.ChatModelPort;
import com.lawrence.supportagent.model.ModelInvocationSecurity;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.user.UserRole;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 验证建议工单草稿在安全校验通过前不会进入写用例。 */
class SuggestedTicketOutputSecurityTest {
    /** 草稿泄漏随机标记时必须立即拒绝且不能创建工单。 */
    @Test
    void shouldRejectCanaryLeakBeforeTicketPersistence() {
        ConversationStorePort conversations = mock(ConversationStorePort.class);
        ChatModelPort model = mock(ChatModelPort.class);
        TicketCommandUseCase commands = mock(TicketCommandUseCase.class);
        UUID conversationId = UUID.randomUUID();
        UUID suggestionId = UUID.randomUUID();
        when(conversations.claimSuggestion(any(), any(), any(), any())).thenReturn(
                new SuggestionClaim("CLAIMED", UUID.randomUUID(), "用户当前问题：启动失败",
                        UUID.randomUUID(), null));
        when(model.generateTicketDraft(any(), any(ModelInvocationSecurity.class)))
                .thenAnswer(invocation -> new ChatModelPort.TicketDraft(
                        invocation.<ModelInvocationSecurity>getArgument(1).canary(),
                        "应用启动失败", "已检查日志"));
        SuggestedTicketUseCase useCase = new SuggestedTicketUseCase(conversations, model,
                commands, mock(TicketQueryUseCase.class),
                () -> Instant.parse("2026-09-21T00:00:00Z"));
        AuthenticatedUser actor = new AuthenticatedUser(UUID.randomUUID(), "tester", UserRole.USER);

        ApplicationException failure = assertThrows(ApplicationException.class,
                () -> useCase.create(actor, conversationId, suggestionId, "key-1"));

        assertEquals(ErrorCode.CHAT_ANSWER_VALIDATION_FAILED, failure.errorCode());
        verifyNoInteractions(commands);
    }
}
