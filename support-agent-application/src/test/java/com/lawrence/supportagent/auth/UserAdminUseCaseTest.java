package com.lawrence.supportagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.auth.port.AccessTokenPort;
import com.lawrence.supportagent.auth.port.PasswordHashPort;
import com.lawrence.supportagent.auth.port.UserRepository;
import com.lawrence.supportagent.idempotency.IdempotentExecutor;
import com.lawrence.supportagent.idempotency.IdempotentResource;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.user.UserAccount;
import com.lawrence.supportagent.user.UserRole;
import com.lawrence.supportagent.user.UserStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 验证管理员创建用户、禁用撤销和越权防护。 */
@ExtendWith(MockitoExtension.class)
class UserAdminUseCaseTest {
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");
    private static final UUID ADMIN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(ADMIN_ID, "admin", UserRole.ADMIN);
    @Mock private UserRepository users;
    @Mock private PasswordHashPort passwords;
    @Mock private AccessTokenPort tokens;
    @Mock private IdempotentExecutor idempotency;
    private UserAdminUseCase useCase;

    /** 创建使用固定标识和时间的管理员用例。 */
    @BeforeEach
    void setUp() {
        useCase = new UserAdminUseCase(users, passwords, tokens, () -> USER_ID, () -> NOW,
                idempotency);
    }

    /** 管理员创建用户时只持久化哈希且响应不包含密码字段。 */
    @Test
    void shouldCreateActiveUserWithPasswordHash() {
        when(users.findByUsername("alice")).thenReturn(Optional.empty());
        when(passwords.hash("strong-password")).thenReturn("bcrypt-hash");
        when(users.save(any())).thenAnswer(invocation -> {
            UserAccount value = invocation.getArgument(0);
            return new UserAccount(2L, value.userId(), value.username(), value.displayName(),
                    value.passwordHash(), value.role(), value.status(), value.version(),
                    value.passwordChangedAt(), value.createdBy(), value.createdAt(),
                    value.updatedBy(), value.updatedAt());
        });
        when(idempotency.execute(any(), any(), any())).thenAnswer(invocation -> {
            Supplier<IdempotentResource<UserView>> action = invocation.getArgument(1);
            return action.get().value();
        });

        UserView created = useCase.create(ADMIN, "Alice", "Alice", "strong-password",
                UserRole.USER, "create-alice");

        assertThat(created.userId()).isEqualTo(USER_ID);
        assertThat(created.status()).isEqualTo(UserStatus.ACTIVE);
        verify(passwords).hash("strong-password");
    }

    /** 普通用户不得调用用户管理能力。 */
    @Test
    void shouldRejectNonAdministrator() {
        AuthenticatedUser user = new AuthenticatedUser(USER_ID, "alice", UserRole.USER);

        assertThatThrownBy(() -> useCase.create(user, "bob", "Bob", "strong-password",
                UserRole.USER, "create-bob"))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
        verify(users, never()).save(any());
    }

    /** 禁用用户必须递增版本并立即撤销该用户全部 Token。 */
    @Test
    void shouldRevokeAllTokensWhenDisablingUser() {
        UserAccount account = account(USER_ID, UserStatus.ACTIVE, 0);
        when(users.findByUserId(USER_ID)).thenReturn(Optional.of(account));
        when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserView changed = useCase.changeStatus(ADMIN, USER_ID, UserStatus.DISABLED, 0);

        assertThat(changed.version()).isEqualTo(1);
        verify(tokens).revokeAll(USER_ID);
    }

    /** 管理员不得通过当前接口禁用自己。 */
    @Test
    void shouldRejectSelfDisable() {
        assertThatThrownBy(() -> useCase.changeStatus(ADMIN, ADMIN_ID, UserStatus.DISABLED, 0))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.AUTH_SELF_DISABLE_FORBIDDEN));
    }

    /** 创建具备给定状态和版本的测试用户。 */
    private UserAccount account(UUID userId, UserStatus status, long version) {
        return new UserAccount(1L, userId, "alice", "Alice", "bcrypt-hash",
                UserRole.USER, status, version, NOW, ADMIN_ID.toString(), NOW,
                ADMIN_ID.toString(), NOW);
    }
}
