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

/** 验证本地登录、限流、禁用、注销和本人查询边界。 */
@ExtendWith(MockitoExtension.class)
class AuthenticationUseCaseTest {
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");
    private static final UUID USER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    @Mock private UserRepository users;
    @Mock private PasswordHashPort passwords;
    @Mock private AccessTokenPort tokens;
    @Mock private LoginAttemptPort attempts;
    private AuthenticationUseCase useCase;

    /** 为每个测试建立固定两小时生命周期的认证用例。 */
    @BeforeEach
    void setUp() {
        useCase = new AuthenticationUseCase(users, passwords, tokens, attempts,
                () -> NOW, Duration.ofHours(2));
    }

    /** 正确凭据应清除失败记录并签发只返回一次的 Token。 */
    @Test
    void shouldLoginWithValidCredentials() {
        UserAccount account = account(UserStatus.ACTIVE);
        when(users.findByUsername("alice")).thenReturn(Optional.of(account));
        when(passwords.matches("correct-password", "bcrypt-hash")).thenReturn(true);
        when(tokens.issue(any(), any(), any())).thenReturn(
                new AccessTokenPort.IssuedToken("raw-token", NOW.plusSeconds(7200)));

        LoginResult result = useCase.login(" Alice ", "correct-password", "127.0.0.1");

        assertThat(result.accessToken()).isEqualTo("raw-token");
        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(7200));
        verify(attempts).clear("alice", "127.0.0.1");
    }

    /** 用户不存在和错误密码必须返回相同稳定错误且记录失败。 */
    @Test
    void shouldHideWhetherUserExists() {
        when(users.findByUsername("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.login("missing", "wrong-password", "client"))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS));
        verify(attempts).recordFailure(any(), any(), any(Integer.class), any(Duration.class));
        verify(passwords).matches("wrong-password", null);
        verify(tokens, never()).issue(any(), any(), any());
    }

    /** 非法用户名格式也必须返回相同认证错误并计入统一限流。 */
    @Test
    void shouldHideInvalidUsernameFormat() {
        assertThatThrownBy(() -> useCase.login("INVALID SPACE", "wrong-password", "client"))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS));
        verify(attempts).recordFailure("invalid space", "client", 5, Duration.ofMinutes(15));
        verify(users, never()).findByUsername(any());
    }

    /** 禁用账号即使密码正确也不得获得 Token。 */
    @Test
    void shouldRejectDisabledAccount() {
        when(users.findByUsername("alice")).thenReturn(Optional.of(account(UserStatus.DISABLED)));
        when(passwords.matches("correct-password", "bcrypt-hash")).thenReturn(true);

        assertThatThrownBy(() -> useCase.login("alice", "correct-password", "client"))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS));
        verify(attempts).recordFailure(any(), any(), any(Integer.class), any(Duration.class));
        verify(tokens, never()).issue(any(), any(), any());
    }

    /** 已达到失败阈值时必须在查询用户和校验密码前返回限流错误。 */
    @Test
    void shouldRateLimitBeforeCredentialVerification() {
        when(attempts.blocked("alice", "client", 5)).thenReturn(true);

        assertThatThrownBy(() -> useCase.login("alice", "password-value", "client"))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.AUTH_RATE_LIMITED));
        verify(users, never()).findByUsername(any());
    }

    /** 注销必须把当前原始 Token 交给 Token 端口幂等撤销。 */
    @Test
    void shouldLogoutCurrentToken() {
        useCase.logout("raw-token");
        verify(tokens).revoke("raw-token");
    }

    /** 创建稳定的测试用户聚合。 */
    private UserAccount account(UserStatus status) {
        return new UserAccount(1L, USER_ID, "alice", "Alice", "bcrypt-hash",
                UserRole.USER, status, 0, NOW, "admin", NOW, "admin", NOW);
    }
}
