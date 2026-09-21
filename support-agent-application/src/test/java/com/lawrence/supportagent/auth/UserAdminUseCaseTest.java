package com.lawrence.supportagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.auth.port.AccessTokenPort;
import com.lawrence.supportagent.auth.port.LoginAttemptPort;
import com.lawrence.supportagent.auth.port.PasswordHashPort;
import com.lawrence.supportagent.auth.port.SecurityEventPort;
import com.lawrence.supportagent.auth.port.UserRepository;
import com.lawrence.supportagent.idempotency.IdempotentExecutor;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.user.UserAccount;
import com.lawrence.supportagent.user.UserRole;
import com.lawrence.supportagent.user.UserStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 验证管理员列表、角色、重置、解锁和 Token 治理。 */
@ExtendWith(MockitoExtension.class)
class UserAdminUseCaseTest {
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static final UUID ADMIN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(ADMIN_ID, "admin", UserRole.ADMIN);
    private static final AuthenticatedUser USER = new AuthenticatedUser(USER_ID, "alice", UserRole.USER);
    @Mock private UserRepository users;
    @Mock private PasswordHashPort passwords;
    @Mock private AccessTokenPort tokens;
    @Mock private IdempotentExecutor idempotency;
    @Mock private LoginAttemptPort attempts;
    @Mock private SecurityEventPort events;
    private UserAdminUseCase useCase;

    /** 建立管理员用户治理用例。 */
    @BeforeEach void setUp() {
        useCase = new UserAdminUseCase(users, passwords, tokens, () -> USER_ID, () -> NOW,
                idempotency, attempts, events);
    }

    /** 用户列表必须传递筛选和分页并返回安全视图。 */
    @Test void shouldListUsersWithFilters() {
        when(users.findPage(UserRole.USER, UserStatus.ACTIVE, 20, 20))
                .thenReturn(List.of(account(USER_ID, UserRole.USER, false, null, 0)));
        when(users.countPage(UserRole.USER, UserStatus.ACTIVE)).thenReturn(21L);

        UserPage page = useCase.list(ADMIN, UserRole.USER, UserStatus.ACTIVE, 2, 20);

        assertThat(page.total()).isEqualTo(21);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.items()).hasSize(1);
    }

    /** 用户分页必须遵守全项目统一的从 1 开始契约。 */
    @Test void shouldRejectZeroBasedPage() {
        assertThatThrownBy(() -> useCase.list(ADMIN, null, null, 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("页码不能小于 1");
    }

    /** 非管理员不得访问任何用户治理能力。 */
    @Test void shouldRejectNonAdministrator() {
        assertThatThrownBy(() -> useCase.list(USER, null, null, 1, 20))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
    }

    /** 管理员不得降低自己的角色。 */
    @Test void shouldRejectSelfRoleDowngrade() {
        assertThatThrownBy(() -> useCase.changeRole(ADMIN, ADMIN_ID, UserRole.USER, 0))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(
                                ErrorCode.AUTH_SELF_ROLE_CHANGE_FORBIDDEN));
    }

    /** 角色变化必须递增版本并撤销旧角色 Token。 */
    @Test void shouldChangeRoleAndRevokeTokens() {
        when(users.findByUserId(USER_ID)).thenReturn(Optional.of(
                account(USER_ID, UserRole.USER, false, null, 0)));
        when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserView changed = useCase.changeRole(ADMIN, USER_ID, UserRole.ADMIN, 0);

        assertThat(changed.role()).isEqualTo(UserRole.ADMIN);
        verify(tokens).revokeAll(USER_ID);
    }

    /** 管理员重置密码必须标记强制改密并撤销全部 Token。 */
    @Test void shouldResetPasswordAsOneTimePassword() {
        when(users.findByUserId(USER_ID)).thenReturn(Optional.of(
                account(USER_ID, UserRole.USER, false, null, 3)));
        when(passwords.hash("temporary-pass")).thenReturn("new-hash");
        when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserView changed = useCase.resetPassword(ADMIN, USER_ID, "temporary-pass", 3);

        assertThat(changed.mustChangePassword()).isTrue();
        verify(tokens).revokeAll(USER_ID);
        verify(attempts).clearUsername("alice");
    }

    /** 解锁不得改变禁用状态，只清空锁定和用户名失败计数。 */
    @Test void shouldUnlockWithoutEnablingDisabledAccount() {
        when(users.findByUserId(USER_ID)).thenReturn(Optional.of(
                account(USER_ID, UserRole.USER, false, NOW.plusSeconds(900), 2, UserStatus.DISABLED)));
        when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserView unlocked = useCase.unlock(ADMIN, USER_ID, 2);

        assertThat(unlocked.status()).isEqualTo(UserStatus.DISABLED);
        assertThat(unlocked.lockedUntil()).isNull();
        verify(attempts).clearUsername("alice");
    }

    /** 创建活动测试用户。 */
    private UserAccount account(UUID userId, UserRole role, boolean mustChange,
                                Instant lockedUntil, long version) {
        return account(userId, role, mustChange, lockedUntil, version, UserStatus.ACTIVE);
    }

    /** 创建具备完整安全状态的测试用户。 */
    private UserAccount account(UUID userId, UserRole role, boolean mustChange,
                                Instant lockedUntil, long version, UserStatus status) {
        return new UserAccount(1L, userId, "alice", "Alice", "bcrypt-hash", role,
                status, version, NOW, mustChange, lockedUntil, ADMIN_ID.toString(), NOW,
                ADMIN_ID.toString(), NOW);
    }
}
