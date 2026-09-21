package com.lawrence.supportagent.auth;

import com.lawrence.supportagent.auth.port.AccessTokenPort;
import com.lawrence.supportagent.auth.port.LoginAttemptPort;
import com.lawrence.supportagent.auth.port.PasswordHashPort;
import com.lawrence.supportagent.auth.port.SecurityEventPort;
import com.lawrence.supportagent.auth.port.UserRepository;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.user.UserAccount;
import com.lawrence.supportagent.user.UserStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

/** 编排本地登录、受限 Token、本人改密和全部 Token 撤销。 */
public class AuthenticationUseCase {
    private final UserRepository users;
    private final PasswordHashPort passwords;
    private final AccessTokenPort tokens;
    private final LoginAttemptPort attempts;
    private final SecurityEventPort events;
    private final TimeProvider time;
    private final Duration tokenTtl;
    private final int maximumActiveTokens;

    /** 注入用户、密码、Token、失败治理、审计、时间和 Token 参数。 */
    public AuthenticationUseCase(UserRepository users, PasswordHashPort passwords,
                                 AccessTokenPort tokens, LoginAttemptPort attempts,
                                 SecurityEventPort events, TimeProvider time,
                                 Duration tokenTtl, int maximumActiveTokens) {
        if (tokenTtl == null || tokenTtl.isZero() || tokenTtl.isNegative()
                || maximumActiveTokens <= 0) {
            throw new IllegalArgumentException("Token TTL 和并发 Token 上限必须大于 0");
        }
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.attempts = attempts;
        this.events = events;
        this.time = time;
        this.tokenTtl = tokenTtl;
        this.maximumActiveTokens = maximumActiveTokens;
    }

    /** 校验凭据并签发固定生命周期、数量受控的不透明 Token。 */
    public LoginResult login(String username, String password, String clientSource) {
        Instant now = time.now();
        String loginSubject = normalizeLoginSubject(username);
        String source = normalizeSource(clientSource);
        if (attempts.blockedUntil(loginSubject, source, now).isPresent()) {
            events.record(SecurityEventType.LOGIN_BLOCKED, null, "ANONYMOUS", "DENIED",
                    "BACKOFF", sourceHash(source), now);
            throw rateLimited();
        }
        UserAccount account = loadAccount(username);
        String suppliedPassword = validatePasswordForLogin(password, account, loginSubject, source, now);
        boolean passwordMatches = passwords.matches(suppliedPassword,
                account == null ? null : account.passwordHash());
        if (account == null || !passwordMatches || account.status() != UserStatus.ACTIVE) {
            recordFailure(account, loginSubject, source, now);
            throw invalidCredentials();
        }
        if (account.lockedAt(now)) {
            events.record(SecurityEventType.LOGIN_BLOCKED, account.userId(), "ANONYMOUS", "DENIED",
                    "ACCOUNT_LOCKED", sourceHash(source), now);
            throw rateLimited();
        }
        if (account.lockedUntil() != null) {
            users.clearExpiredLock(account.userId(), now, account.userId().toString());
            account = users.findByUserId(account.userId()).orElseThrow(this::unauthorized);
            if (account.status() != UserStatus.ACTIVE || account.lockedAt(now)) {
                throw invalidCredentials();
            }
        }
        attempts.clear(loginSubject, source);
        AuthenticatedUser user = new AuthenticatedUser(account.userId(), account.username(),
                account.role(), account.mustChangePassword());
        AccessTokenPort.IssuedToken issued = tokens.issue(user, now, tokenTtl, maximumActiveTokens);
        events.record(SecurityEventType.LOGIN_SUCCEEDED, account.userId(), account.userId().toString(),
                "SUCCEEDED", account.mustChangePassword() ? "PASSWORD_CHANGE_REQUIRED" : "STANDARD",
                sourceHash(source), now);
        return new LoginResult(issued.rawToken(), "Bearer", issued.expiresAt(), UserView.from(account));
    }

    /** 校验旧密码后更新本人密码，并撤销包括当前 Token 在内的全部旧 Token。 */
    public UserView changePassword(AuthenticatedUser actor, String oldPassword, String newPassword) {
        UserAccount current = requireActive(actor);
        String validatedOld = PasswordPolicy.validate(oldPassword);
        String validatedNew = PasswordPolicy.validate(newPassword);
        if (!passwords.matches(validatedOld, current.passwordHash())) {
            throw invalidCredentials();
        }
        if (passwords.matches(validatedNew, current.passwordHash())) {
            throw new ApplicationException(ErrorCode.AUTH_PASSWORD_REUSED, "新密码不能与当前密码相同");
        }
        Instant now = time.now();
        UserAccount saved = users.save(current.changePassword(passwords.hash(validatedNew),
                actor.userId().toString(), now));
        attempts.clearUsername(current.username());
        tokens.revokeAll(current.userId());
        events.record(SecurityEventType.PASSWORD_CHANGED, current.userId(), actor.userId().toString(),
                "SUCCEEDED", "SELF_SERVICE", null, now);
        return UserView.from(saved);
    }

    /** 幂等撤销当前原始 Token。 */
    public void logout(String rawToken) {
        tokens.revoke(rawToken);
    }

    /** 撤销本人当前全部 Token，调用完成后当前请求 Token 也失效。 */
    public void revokeAll(AuthenticatedUser actor) {
        requireActive(actor);
        tokens.revokeAll(actor.userId());
        events.record(SecurityEventType.TOKENS_REVOKED, actor.userId(), actor.userId().toString(),
                "SUCCEEDED", "SELF_SERVICE", null, time.now());
    }

    /** 重新读取本人账号，避免响应 Token 中的过期展示信息。 */
    public UserView me(AuthenticatedUser actor) {
        return UserView.from(requireActive(actor));
    }

    /** 加载格式合法的用户；非法格式按不存在处理以防账号枚举。 */
    private UserAccount loadAccount(String username) {
        try {
            return users.findByUsername(UserAccount.normalizeUsername(username)).orElse(null);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** 验证登录密码格式；格式错误仍进入相同失败治理。 */
    private String validatePasswordForLogin(String password, UserAccount account, String subject,
                                            String source, Instant now) {
        try {
            return PasswordPolicy.validate(password);
        } catch (IllegalArgumentException exception) {
            recordFailure(account, subject, source, now);
            throw invalidCredentials();
        }
    }

    /** 记录失败退避，并在已知账号达到锁定阈值时持久化截止时间。 */
    private void recordFailure(UserAccount account, String subject, String source, Instant now) {
        LoginAttemptPort.LoginAttemptDecision decision = attempts.recordFailure(subject, source, now);
        if (account != null && decision.blockedUntil() != null
                && (account.lockedUntil() == null
                || decision.blockedUntil().isAfter(account.lockedUntil()))) {
            users.extendLock(account.userId(), decision.blockedUntil(), "AUTHENTICATION", now);
        }
        events.record(SecurityEventType.LOGIN_FAILED, account == null ? null : account.userId(),
                "ANONYMOUS", "DENIED", decision.blockedUntil() == null ? "INVALID_CREDENTIALS"
                        : "BACKOFF_APPLIED", sourceHash(source), now);
    }

    /** 读取并校验当前账号仍可执行本人安全操作。 */
    private UserAccount requireActive(AuthenticatedUser actor) {
        if (actor == null) throw unauthorized();
        UserAccount account = users.findByUserId(actor.userId()).orElseThrow(this::unauthorized);
        if (account.status() != UserStatus.ACTIVE) throw unauthorized();
        return account;
    }

    /** 创建不区分账号存在性的稳定认证错误。 */
    private ApplicationException invalidCredentials() {
        return new ApplicationException(ErrorCode.AUTH_INVALID_CREDENTIALS, "用户名或密码错误");
    }

    /** 创建临时退避错误。 */
    private ApplicationException rateLimited() {
        return new ApplicationException(ErrorCode.AUTH_RATE_LIMITED, "登录尝试过于频繁，请稍后重试");
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

    /** 规范化空客户端来源，避免空 Redis 主体。 */
    private String normalizeSource(String source) {
        return source == null || source.isBlank() ? "unknown" : source.strip();
    }

    /** 对客户端来源生成不可逆审计摘要，禁止保存原始地址。 */
    private String sourceHash(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 不支持 SHA-256", exception);
        }
    }
}
