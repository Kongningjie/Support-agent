package com.lawrence.supportagent.auth;

import com.lawrence.supportagent.auth.port.AccessTokenPort;
import com.lawrence.supportagent.auth.port.LoginAttemptPort;
import com.lawrence.supportagent.auth.port.PasswordHashPort;
import com.lawrence.supportagent.auth.port.UserRepository;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.user.UserAccount;
import com.lawrence.supportagent.user.UserStatus;
import java.time.Duration;
import java.util.Locale;

/** 编排本地用户名密码登录、Token 签发、注销和本人信息查询。 */
public class AuthenticationUseCase {
    private static final int MAXIMUM_FAILURES = 5;
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    private final UserRepository users;
    private final PasswordHashPort passwords;
    private final AccessTokenPort tokens;
    private final LoginAttemptPort attempts;
    private final TimeProvider time;
    private final Duration tokenTtl;

    /** 注入用户、密码、Token、限流、时间和固定 Token 生命周期。 */
    public AuthenticationUseCase(UserRepository users, PasswordHashPort passwords,
                                 AccessTokenPort tokens, LoginAttemptPort attempts,
                                 TimeProvider time, Duration tokenTtl) {
        if (tokenTtl == null || tokenTtl.isZero() || tokenTtl.isNegative()) {
            throw new IllegalArgumentException("Token TTL 必须大于 0");
        }
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.attempts = attempts;
        this.time = time;
        this.tokenTtl = tokenTtl;
    }

    /** 校验凭据并签发固定两小时、不滑动续期的不透明 Token。 */
    public LoginResult login(String username, String password, String clientSource) {
        String loginSubject = normalizeLoginSubject(username);
        String source = clientSource == null || clientSource.isBlank() ? "unknown" : clientSource;
        if (attempts.blocked(loginSubject, source, MAXIMUM_FAILURES)) {
            throw new ApplicationException(ErrorCode.AUTH_RATE_LIMITED, "登录尝试过于频繁，请稍后重试");
        }
        String normalized;
        try {
            normalized = UserAccount.normalizeUsername(username);
        } catch (IllegalArgumentException exception) {
            attempts.recordFailure(loginSubject, source, MAXIMUM_FAILURES, FAILURE_WINDOW);
            throw invalidCredentials();
        }
        String suppliedPassword;
        try {
            suppliedPassword = PasswordPolicy.validate(password);
        } catch (IllegalArgumentException exception) {
            attempts.recordFailure(loginSubject, source, MAXIMUM_FAILURES, FAILURE_WINDOW);
            throw invalidCredentials();
        }
        UserAccount account = users.findByUsername(normalized).orElse(null);
        String storedHash = account == null ? null : account.passwordHash();
        boolean passwordMatches = passwords.matches(suppliedPassword, storedHash);
        if (account == null || !passwordMatches) {
            attempts.recordFailure(loginSubject, source, MAXIMUM_FAILURES, FAILURE_WINDOW);
            throw invalidCredentials();
        }
        if (account.status() != UserStatus.ACTIVE) {
            attempts.recordFailure(loginSubject, source, MAXIMUM_FAILURES, FAILURE_WINDOW);
            throw invalidCredentials();
        }
        attempts.clear(loginSubject, source);
        AuthenticatedUser user = new AuthenticatedUser(account.userId(), account.username(), account.role());
        AccessTokenPort.IssuedToken issued = tokens.issue(user, time.now(), tokenTtl);
        return new LoginResult(issued.rawToken(), "Bearer", issued.expiresAt(), UserView.from(account));
    }

    /** 幂等撤销当前原始 Token。 */
    public void logout(String rawToken) {
        tokens.revoke(rawToken);
    }

    /** 重新读取本人账号，避免响应 Token 中的过期展示信息。 */
    public UserView me(AuthenticatedUser actor) {
        UserAccount account = users.findByUserId(actor.userId()).orElseThrow(this::unauthorized);
        if (account.status() != UserStatus.ACTIVE) {
            throw unauthorized();
        }
        return UserView.from(account);
    }

    /** 创建不区分用户不存在、密码错误和禁用状态的稳定认证错误。 */
    private ApplicationException invalidCredentials() {
        return new ApplicationException(ErrorCode.AUTH_INVALID_CREDENTIALS, "用户名或密码错误");
    }

    /** 创建不泄露用户状态的未认证错误。 */
    private ApplicationException unauthorized() {
        return new ApplicationException(ErrorCode.AUTH_UNAUTHORIZED, "认证信息无效或已经过期");
    }

    /** 为格式错误与合法用户名生成相同的限流主体规范化值。 */
    private String normalizeLoginSubject(String username) {
        if (username == null) return "<null>";
        String normalized = username.strip().toLowerCase(Locale.ROOT);
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }
}
