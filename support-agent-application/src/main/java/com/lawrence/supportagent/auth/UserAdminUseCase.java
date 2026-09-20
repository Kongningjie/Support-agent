package com.lawrence.supportagent.auth;

import com.lawrence.supportagent.auth.port.AccessTokenPort;
import com.lawrence.supportagent.auth.port.PasswordHashPort;
import com.lawrence.supportagent.auth.port.UserRepository;
import com.lawrence.supportagent.idempotency.IdempotencyCommand;
import com.lawrence.supportagent.idempotency.IdempotentExecutor;
import com.lawrence.supportagent.idempotency.IdempotentResource;
import com.lawrence.supportagent.idempotency.RequestFingerprint;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.sharedkernel.port.UuidGenerator;
import com.lawrence.supportagent.user.UserAccount;
import com.lawrence.supportagent.user.UserRole;
import com.lawrence.supportagent.user.UserStatus;
import java.time.Duration;

/** 提供管理员创建用户和启停账号的阶段 11 边界。 */
public class UserAdminUseCase {
    private static final Duration IDEMPOTENCY_LEASE = Duration.ofSeconds(30);
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofDays(7);
    private final UserRepository users;
    private final PasswordHashPort passwords;
    private final AccessTokenPort tokens;
    private final UuidGenerator ids;
    private final TimeProvider time;
    private final IdempotentExecutor idempotency;

    /** 注入用户、密码、Token、标识、时间和外部幂等执行端口。 */
    public UserAdminUseCase(UserRepository users, PasswordHashPort passwords,
                            AccessTokenPort tokens, UuidGenerator ids, TimeProvider time,
                            IdempotentExecutor idempotency) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.ids = ids;
        this.time = time;
        this.idempotency = idempotency;
    }

    /** 由管理员创建初始状态为 ACTIVE 的本地用户。 */
    public UserView create(AuthenticatedUser actor, String username, String displayName,
                           String password, UserRole role, String idempotencyKey) {
        requireAdmin(actor);
        String normalized = UserAccount.normalizeUsername(username);
        String normalizedDisplayName = requireDisplayName(displayName);
        String suppliedPassword = PasswordPolicy.validate(password);
        if (role == null) throw new IllegalArgumentException("用户角色不能为空");
        IdempotencyCommand command = new IdempotencyCommand(actor.userId().toString(),
                "USER_CREATE", requireIdempotencyKey(idempotencyKey),
                RequestFingerprint.sha256(normalized, normalizedDisplayName, role.name()),
                IDEMPOTENCY_LEASE, IDEMPOTENCY_RETENTION);
        return idempotency.execute(command, () -> {
            UserAccount created = createOnce(actor, normalized, normalizedDisplayName,
                    suppliedPassword, role);
            return new IdempotentResource<>("USER", created.id(), UserView.from(created));
        }, this::loadReplay);
    }

    /** 执行一次真实用户创建并拒绝已有用户名。 */
    private UserAccount createOnce(AuthenticatedUser actor, String username, String displayName,
                                   String password, UserRole role) {
        if (users.findByUsername(username).isPresent()) {
            throw new ApplicationException(ErrorCode.AUTH_USERNAME_CONFLICT, "用户名已经存在");
        }
        UserAccount account = UserAccount.create(ids.generate(), username, displayName,
                passwords.hash(password), role, actor.userId().toString(), time.now());
        return users.save(account);
    }

    /** 按幂等记录中的内部主键重放安全用户视图。 */
    private UserView loadReplay(long id) {
        return users.findById(id).map(UserView::from).orElseThrow(() ->
                new ApplicationException(ErrorCode.AUTH_USER_NOT_FOUND, "用户不存在"));
    }

    /** 校验展示名称并生成稳定请求指纹输入。 */
    private String requireDisplayName(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("展示名称不能为空");
        String normalized = value.strip();
        if (normalized.length() > 100) throw new IllegalArgumentException("展示名称不能超过 100 个字符");
        return normalized;
    }

    /** 校验客户端幂等键。 */
    private String requireIdempotencyKey(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("幂等键不能为空");
        String normalized = value.strip();
        if (normalized.length() > 160) throw new IllegalArgumentException("幂等键不能超过 160 个字符");
        return normalized;
    }

    /** 由管理员启用或禁用账号；禁用后立即撤销该用户全部 Token。 */
    public UserView changeStatus(AuthenticatedUser actor, java.util.UUID userId,
                                 UserStatus status, long version) {
        requireAdmin(actor);
        if (actor.userId().equals(userId) && status == UserStatus.DISABLED) {
            throw new ApplicationException(ErrorCode.AUTH_SELF_DISABLE_FORBIDDEN, "管理员不能禁用自己");
        }
        UserAccount current = users.findByUserId(userId).orElseThrow(() ->
                new ApplicationException(ErrorCode.AUTH_USER_NOT_FOUND, "用户不存在"));
        if (version < 0 || current.version() != version) {
            throw new ApplicationException(ErrorCode.AUTH_USER_VERSION_CONFLICT, "用户版本已变化");
        }
        UserAccount saved = users.save(current.changeStatus(status,
                actor.userId().toString(), time.now()));
        if (status == UserStatus.DISABLED) {
            tokens.revokeAll(userId);
        }
        return UserView.from(saved);
    }

    /** 校验调用者为管理员。 */
    private void requireAdmin(AuthenticatedUser actor) {
        if (actor == null || !actor.administrator()) {
            throw new ApplicationException(ErrorCode.AUTH_FORBIDDEN, "当前用户没有管理员权限");
        }
    }
}
