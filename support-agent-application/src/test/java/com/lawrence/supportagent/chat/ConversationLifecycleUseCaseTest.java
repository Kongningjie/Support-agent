package com.lawrence.supportagent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.chat.port.ConversationStorePort;
import com.lawrence.supportagent.chat.port.ConversationStorePort.LifecyclePage;
import com.lawrence.supportagent.chat.port.ConversationStorePort.LifecycleSnapshot;
import com.lawrence.supportagent.user.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 验证会话生命周期用例的分页、管理员读取边界和所有者写操作。 */
@ExtendWith(MockitoExtension.class)
class ConversationLifecycleUseCaseTest {
    private static final Instant NOW = Instant.parse("2026-09-20T08:00:00Z");
    private static final UUID USER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID CONVERSATION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    @Mock private ConversationStorePort store;
    private ConversationLifecycleUseCase useCase;

    /** 使用固定时间创建用例。 */
    @BeforeEach
    void setUp() {
        useCase = new ConversationLifecycleUseCase(store, () -> NOW);
    }

    /** 列表应只查询当前用户并正确计算分页元数据。 */
    @Test
    void shouldListOnlyCurrentUserConversations() {
        when(store.listLifecycle(USER_ID, 20, 20, NOW))
                .thenReturn(new LifecyclePage(List.of(snapshot(USER_ID)), 21));

        ConversationPage result = useCase.list(user(), 2, 20);

        assertThat(result.items()).hasSize(1);
        assertThat(result.totalPages()).isEqualTo(2);
        verify(store).listLifecycle(USER_ID, 20, 20, NOW);
    }

    /** 管理员详情读取应显式传递跨用户查看资格。 */
    @Test
    void shouldAllowAdministratorToRequestDetailsById() {
        AuthenticatedUser administrator = new AuthenticatedUser(USER_ID, "admin", UserRole.ADMIN);
        when(store.lifecycleDetails(USER_ID, true, CONVERSATION_ID, 20, NOW))
                .thenReturn(snapshot(UUID.randomUUID()));

        ConversationDetails result = useCase.details(administrator, CONVERSATION_ID);

        assertThat(result.overview().conversationId()).isEqualTo(CONVERSATION_ID);
        verify(store).lifecycleDetails(USER_ID, true, CONVERSATION_ID, 20, NOW);
    }

    /** 重置和删除必须始终使用当前认证用户作为所有者。 */
    @Test
    void shouldResetAndDeleteAsCurrentOwner() {
        when(store.reset(USER_ID, CONVERSATION_ID, 3, NOW)).thenReturn(snapshot(USER_ID));

        ConversationOverview reset = useCase.reset(user(), CONVERSATION_ID, 3);
        useCase.delete(user(), CONVERSATION_ID, 0);

        assertThat(reset.generation()).isEqualTo(1);
        verify(store).reset(USER_ID, CONVERSATION_ID, 3, NOW);
        verify(store).delete(USER_ID, CONVERSATION_ID, 0, NOW);
    }

    /** 非法分页和负版本必须在访问存储前被拒绝。 */
    @Test
    void shouldRejectInvalidPaginationAndVersion() {
        assertThatThrownBy(() -> useCase.list(user(), 0, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.reset(user(), CONVERSATION_ID, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 创建普通认证用户。 */
    private AuthenticatedUser user() {
        return new AuthenticatedUser(USER_ID, "user", UserRole.USER);
    }

    /** 创建稳定生命周期快照。 */
    private LifecycleSnapshot snapshot(UUID ownerUserId) {
        return new LifecycleSnapshot(CONVERSATION_ID, ownerUserId, ConversationLifecycleStatus.IDLE,
                0, 1, 0, NOW, NOW.plusSeconds(604800), List.of());
    }
}
