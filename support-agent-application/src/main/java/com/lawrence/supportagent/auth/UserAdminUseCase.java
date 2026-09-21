package com.lawrence.supportagent.auth;

import com.lawrence.supportagent.auth.port.AccessTokenPort;
import com.lawrence.supportagent.auth.port.LoginAttemptPort;
import com.lawrence.supportagent.auth.port.PasswordHashPort;
import com.lawrence.supportagent.auth.port.SecurityEventPort;
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

/** 提供管理员创建、查询、角色、状态、密码、解锁和 Token 治理边界。 */
public class UserAdminUseCase {
    private static final Duration IDEMPOTENCY_LEASE = Duration.ofSeconds(30);
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofDays(7);
    private final UserRepository users;
    private final PasswordHashPort passwords;
    private final AccessTokenPort tokens;
    private final UuidGenerator ids;
    private final TimeProvider time;
    private final IdempotentExecutor idempotency;
    private final LoginAttemptPort attempts;
    private final SecurityEventPort events;

    /** 注入用户、密码、Token、标识、时间和外部幂等执行端口。 */
    public UserAdminUseCase(UserRepository users, PasswordHashPort passwords,
                            AccessTokenPort tokens, UuidGenerator ids, TimeProvider time,
                            IdempotentExecutor idempotency, LoginAttemptPort attempts,
                            SecurityEventPort events) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.ids = ids;
        this.time = time;
        this.idempotency = idempotency;
        this.attempts = attempts;
        this.events = events;
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
        UserAccount current = requireVersion(userId, version);
        UserAccount saved = users.save(current.changeStatus(status,
                actor.userId().toString(), time.now()));
        if (status == UserStatus.DISABLED) {
            tokens.revokeAll(userId);
        }
        events.record(SecurityEventType.STATUS_CHANGED, userId, actor.userId().toString(),
                "SUCCEEDED", status.name(), null, time.now());
        return UserView.from(saved);
    }

    /** 按可选角色和状态筛选并分页查询用户。 */
    public UserPage list(AuthenticatedUser actor, UserRole role, UserStatus status,
                         int page, int size) {
        requireAdmin(actor);
        if (page < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("页码不能小于 1，每页数量必须为 1 至 100");
        }
        if (page - 1 > Integer.MAX_VALUE / size) {
            throw new IllegalArgumentException("分页偏移量超出允许范围");
        }
        return new UserPage(users.findPage(role, status, (page - 1) * size, size).stream()
                .map(UserView::from).toList(), page, size, users.countPage(role, status));
    }

    /** 变更指定用户角色；管理员不得降低自己的角色。 */
    public UserView changeRole(AuthenticatedUser actor, java.util.UUID userId,
                               UserRole role, long version) {
        requireAdmin(actor);
        if (role == null) throw new IllegalArgumentException("用户角色不能为空");
        if (actor.userId().equals(userId) && role != UserRole.ADMIN) {
            throw new ApplicationException(ErrorCode.AUTH_SELF_ROLE_CHANGE_FORBIDDEN,
                    "管理员不能降低自己的角色");
        }
        UserAccount current = requireVersion(userId, version);
        UserAccount saved = users.save(current.changeRole(role, actor.userId().toString(), time.now()));
        tokens.revokeAll(userId);
        events.record(SecurityEventType.ROLE_CHANGED, userId, actor.userId().toString(),
                "SUCCEEDED", role.name(), null, time.now());
        return UserView.from(saved);
    }

    /** 管理员设置一次性密码、强制下次改密并撤销全部现有 Token。 */
    public UserView resetPassword(AuthenticatedUser actor, java.util.UUID userId,
                                  String newPassword, long version) {
        requireAdmin(actor);
        String validated = PasswordPolicy.validate(newPassword);
        UserAccount current = requireVersion(userId, version);
        UserAccount saved = users.save(current.resetPassword(passwords.hash(validated),
                actor.userId().toString(), time.now()));
        attempts.clearUsername(current.username());
        tokens.revokeAll(userId);
        events.record(SecurityEventType.PASSWORD_RESET, userId, actor.userId().toString(),
                "SUCCEEDED", "ADMIN_RESET", null, time.now());
        return UserView.from(saved);
    }

    /** 管理员清除账号临时锁定和用户名失败计数，不改变禁用状态。 */
    public UserView unlock(AuthenticatedUser actor, java.util.UUID userId, long version) {
        requireAdmin(actor);
        UserAccount current = requireVersion(userId, version);
        UserAccount saved = current.lockedUntil() == null ? current
                : users.save(current.changeLock(null, actor.userId().toString(), time.now()));
        attempts.clearUsername(current.username());
        events.record(SecurityEventType.ACCOUNT_UNLOCKED, userId, actor.userId().toString(),
                "SUCCEEDED", "ADMIN_UNLOCK", null, time.now());
        return UserView.from(saved);
    }

    /** 管理员撤销指定用户全部 Token，不改变账号状态。 */
    public void revokeAllTokens(AuthenticatedUser actor, java.util.UUID userId) {
        requireAdmin(actor);
        if (users.findByUserId(userId).isEmpty()) {
            throw new ApplicationException(ErrorCode.AUTH_USER_NOT_FOUND, "用户不存在");
        }
        tokens.revokeAll(userId);
        events.record(SecurityEventType.TOKENS_REVOKED, userId, actor.userId().toString(),
                "SUCCEEDED", "ADMIN_ACTION", null, time.now());
    }

    /** 加载用户并统一校验请求版本。 */
    private UserAccount requireVersion(java.util.UUID userId, long version) {
        UserAccount current = users.findByUserId(userId).orElseThrow(() ->
                new ApplicationException(ErrorCode.AUTH_USER_NOT_FOUND, "用户不存在"));
        if (version < 0 || current.version() != version) {
            throw new ApplicationException(ErrorCode.AUTH_USER_VERSION_CONFLICT, "用户版本已变化");
        }
        return current;
    }

    /** 校验调用者为管理员。 */
    private void requireAdmin(AuthenticatedUser actor) {
        if (actor == null || !actor.administrator()) {
            throw new ApplicationException(ErrorCode.AUTH_FORBIDDEN, "当前用户没有管理员权限");
        }
    }
}
