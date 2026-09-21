package com.lawrence.supportagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.auth.port.AccessTokenPort;
import com.lawrence.supportagent.auth.port.LoginAttemptPort;
import com.lawrence.supportagent.auth.port.PasswordHashPort;
import com.lawrence.supportagent.auth.port.SecurityEventPort;
import com.lawrence.supportagent.auth.port.UserRepository;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.user.UserAccount;
import com.lawrence.supportagent.user.UserRole;
import com.lawrence.supportagent.user.UserStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 验证登录退避、受限 Token、本人改密和 Token 治理。 */
@ExtendWith(MockitoExtension.class)
class AuthenticationUseCaseTest {
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static final UUID USER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    @Mock private UserRepository users;
    @Mock private PasswordHashPort passwords;
    @Mock private AccessTokenPort tokens;
    @Mock private LoginAttemptPort attempts;
    @Mock private SecurityEventPort events;
    private AuthenticationUseCase useCase;

    /** 建立固定两小时、最多五个 Token 的认证用例。 */
    @BeforeEach void setUp() {
        useCase = new AuthenticationUseCase(users, passwords, tokens, attempts, events,
                () -> NOW, Duration.ofHours(2), 5);
    }

    /** 正确凭据应签发带强制改密标记且受数量约束的 Token。 */
    @Test void shouldLoginWithRestrictedTokenWhenPasswordChangeRequired() {
        UserAccount account = account(UserStatus.ACTIVE, true, null, 0);
        when(users.findByUsername("alice")).thenReturn(Optional.of(account));
        when(passwords.matches("correct-password", "bcrypt-hash")).thenReturn(true);
        when(tokens.issue(any(), any(), any(), any(Integer.class))).thenReturn(
                new AccessTokenPort.IssuedToken("raw-token", NOW.plusSeconds(7200)));

        LoginResult result = useCase.login(" Alice ", "correct-password", "127.0.0.1");

        assertThat(result.user().mustChangePassword()).isTrue();
        verify(tokens).issue(new AuthenticatedUser(USER_ID, "alice", UserRole.USER, true),
                NOW, Duration.ofHours(2), 5);
        verify(attempts).clear("alice", "127.0.0.1");
    }

    /** 已在 Redis 退避窗口内时不得查询用户或校验密码。 */
    @Test void shouldRateLimitBeforeCredentialVerification() {
        when(attempts.blockedUntil("alice", "client", NOW))
                .thenReturn(Optional.of(NOW.plusSeconds(30)));

        assertThatThrownBy(() -> useCase.login("alice", "password-value", "client"))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.AUTH_RATE_LIMITED));
        verify(users, never()).findByUsername(any());
    }

    /** 禁用账号必须使用通用凭据错误，且不得签发 Token。 */
    @Test void shouldRejectDisabledAccountWithoutLeakingStatus() {
        when(users.findByUsername("alice")).thenReturn(Optional.of(
                account(UserStatus.DISABLED, false, null, 0)));
        when(passwords.matches("correct-password", "bcrypt-hash")).thenReturn(true);
        when(attempts.recordFailure("alice", "client", NOW)).thenReturn(
                new LoginAttemptPort.LoginAttemptDecision(1, null));

        assertThatThrownBy(() -> useCase.login("alice", "correct-password", "client"))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(
                                ErrorCode.AUTH_INVALID_CREDENTIALS));
        verify(tokens, never()).issue(any(), any(), any(), any(Integer.class));
    }

    /** 第三次失败产生锁定时应持久化已知账号锁定截止时间。 */
    @Test void shouldPersistAccountLockFromFailureDecision() {
        UserAccount account = account(UserStatus.ACTIVE, false, null, 0);
        when(users.findByUsername("alice")).thenReturn(Optional.of(account));
        when(passwords.matches("wrong-password", "bcrypt-hash")).thenReturn(false);
        when(attempts.recordFailure("alice", "client", NOW)).thenReturn(
                new LoginAttemptPort.LoginAttemptDecision(3, NOW.plusSeconds(30)));

        assertThatThrownBy(() -> useCase.login("alice", "wrong-password", "client"))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS));
        verify(users).extendLock(USER_ID, NOW.plusSeconds(30), "AUTHENTICATION", NOW);
    }

    /** 本人改密必须验证旧密码、清除强制标记并撤销全部旧 Token。 */
    @Test void shouldChangePasswordAndRevokeAllTokens() {
        UserAccount account = account(UserStatus.ACTIVE, true, NOW.plusSeconds(10), 2);
        when(users.findByUserId(USER_ID)).thenReturn(Optional.of(account));
        when(passwords.matches("current-password", "bcrypt-hash")).thenReturn(true);
        when(passwords.matches("new-password-value", "bcrypt-hash")).thenReturn(false);
        when(passwords.hash("new-password-value")).thenReturn("new-hash");
        when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserView changed = useCase.changePassword(
                new AuthenticatedUser(USER_ID, "alice", UserRole.USER, true),
                "current-password", "new-password-value");

        assertThat(changed.mustChangePassword()).isFalse();
        assertThat(changed.lockedUntil()).isNull();
        verify(tokens).revokeAll(USER_ID);
        verify(attempts).clearUsername("alice");
    }

    /** 新密码与当前密码相同时必须返回冲突且不得写入。 */
    @Test void shouldRejectPasswordReuse() {
        when(users.findByUserId(USER_ID)).thenReturn(Optional.of(
                account(UserStatus.ACTIVE, false, null, 0)));
        when(passwords.matches("current-password", "bcrypt-hash")).thenReturn(true);
        when(passwords.matches("same-password", "bcrypt-hash")).thenReturn(true);

        assertThatThrownBy(() -> useCase.changePassword(
                new AuthenticatedUser(USER_ID, "alice", UserRole.USER),
                "current-password", "same-password"))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.AUTH_PASSWORD_REUSED));
        verify(users, never()).save(any());
    }

    /** 撤销本人全部 Token 必须先确认账号仍为活动状态。 */
    @Test void shouldRevokeAllOwnTokens() {
        when(users.findByUserId(USER_ID)).thenReturn(Optional.of(
                account(UserStatus.ACTIVE, false, null, 0)));
        useCase.revokeAll(new AuthenticatedUser(USER_ID, "alice", UserRole.USER));
        verify(tokens).revokeAll(USER_ID);
    }

    /** 创建具备给定安全状态的测试用户。 */
    private UserAccount account(UserStatus status, boolean mustChangePassword,
                                Instant lockedUntil, long version) {
        return new UserAccount(1L, USER_ID, "alice", "Alice", "bcrypt-hash",
                UserRole.USER, status, version, NOW, mustChangePassword, lockedUntil,
                "admin", NOW, "admin", NOW);
    }
}
