package com.lawrence.supportagent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.idempotency.IdempotentExecutor;
import com.lawrence.supportagent.idempotency.IdempotentResource;
import com.lawrence.supportagent.memory.port.UserMemoryRepository;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.user.UserRole;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 验证长期记忆用例的默认关闭、所有者、版本和状态边界。 */
@ExtendWith(MockitoExtension.class)
class UserMemoryUseCaseTest {
    @Mock private UserMemoryRepository repository;
    @Mock private IdempotentExecutor idempotency;
    private final UUID userId = UUID.randomUUID();
    private final AuthenticatedUser actor = new AuthenticatedUser(userId, "tester", UserRole.USER);
    private final Instant now = Instant.parse("2026-09-20T10:00:00Z");
    private UserMemoryUseCase useCase;

    /** 让幂等执行器在单元测试中直接执行首次业务动作。 */
    @BeforeEach void setUp() {
        lenient().when(idempotency.execute(any(), any(), any())).thenAnswer(invocation -> {
            Supplier<?> action = invocation.getArgument(1);
            IdempotentResource<?> resource = (IdempotentResource<?>) action.get();
            return resource.value();
        });
        useCase = new UserMemoryUseCase(repository, new UserMemoryContentPolicy(),
                idempotency, () -> now);
    }

    /** 没有设置行时必须返回默认关闭和版本零。 */
    @Test void shouldReturnDisabledSettingsByDefault() {
        when(repository.findSettings(userId)).thenReturn(Optional.empty());
        assertEquals(new MemorySettingsView(false, 0), useCase.settings(actor));
    }

    /** 只有属于当前用户且版本一致的候选可以确认。 */
    @Test void shouldConfirmOwnedCandidate() {
        UserMemory proposed = proposed();
        when(repository.findByMemoryId(userId, proposed.memoryId())).thenReturn(Optional.of(proposed));
        when(repository.update(any(UserMemory.class), anyLong())).thenAnswer(invocation -> invocation.getArgument(0));

        UserMemoryView confirmed = useCase.confirm(actor, proposed.memoryId(), 0, "confirm-001");
        assertEquals(MemoryStatus.ACTIVE, confirmed.status());
        assertEquals(1, confirmed.version());
    }

    /** 查询不到当前所有者资源时不得暴露其他用户是否拥有该记忆。 */
    @Test void shouldRejectCrossUserMemory() {
        UUID memoryId = UUID.randomUUID();
        when(repository.findByMemoryId(userId, memoryId)).thenReturn(Optional.empty());
        assertThrows(ApplicationException.class,
                () -> useCase.confirm(actor, memoryId, 0, "confirm-002"));
    }

    /** 更正正文仍必须通过长期记忆敏感信息门禁。 */
    @Test void shouldRejectSensitiveRevision() {
        UserMemory proposed = proposed();
        when(repository.findByMemoryId(userId, proposed.memoryId())).thenReturn(Optional.of(proposed));
        assertThrows(ApplicationException.class, () -> useCase.revise(actor, proposed.memoryId(),
                "api_key=abcdefghijklmnop", null, false, null, 0, "revise-001"));
    }

    /** 已经过期的候选不得被确认成名义上的有效记忆。 */
    @Test void shouldRejectExpiredCandidateConfirmation() {
        UserMemory proposed = proposed();
        UserMemory expired = proposed.revise(proposed.content(), proposed.contentHash(),
                now.minusSeconds(1), false, userId.toString(), now.minusSeconds(2));
        when(repository.findByMemoryId(userId, expired.memoryId())).thenReturn(Optional.of(expired));

        assertThrows(ApplicationException.class,
                () -> useCase.confirm(actor, expired.memoryId(), expired.version(), "confirm-expired"));
    }

    /** 创建当前用户的一条合法候选。 */
    private UserMemory proposed() {
        UserMemory value = UserMemory.propose(UUID.randomUUID(), userId, MemoryType.CONSTRAINT,
                "统一使用 PowerShell 7", "a".repeat(64), UUID.randomUUID(), UUID.randomUUID(), now);
        return new UserMemory(1L, value.memoryId(), value.userId(), value.memoryType(),
                value.content(), value.contentHash(), value.status(), value.pinned(),
                value.sourceConversationId(), value.sourceTurnId(), value.expiresAt(), value.version(),
                value.createdBy(), value.createdAt(), value.confirmedBy(), value.confirmedAt(),
                value.updatedBy(), value.updatedAt(), value.revokedBy(), value.revokedAt());
    }
}
