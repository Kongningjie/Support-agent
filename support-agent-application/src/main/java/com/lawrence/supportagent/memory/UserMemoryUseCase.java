package com.lawrence.supportagent.memory;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.idempotency.IdempotencyCommand;
import com.lawrence.supportagent.idempotency.IdempotentExecutor;
import com.lawrence.supportagent.idempotency.IdempotentResource;
import com.lawrence.supportagent.idempotency.RequestFingerprint;
import com.lawrence.supportagent.memory.UserMemoryContentPolicy.NormalizedMemory;
import com.lawrence.supportagent.memory.port.UserMemoryRepository;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 编排本人长期记忆开关、查询、确认、更正、撤销和永久删除。 */
public class UserMemoryUseCase {
    private static final Duration IDEMPOTENCY_LEASE = Duration.ofSeconds(30);
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofDays(7);
    private final UserMemoryRepository repository;
    private final UserMemoryContentPolicy contentPolicy;
    private final IdempotentExecutor idempotency;
    private final TimeProvider time;

    /** 注入长期记忆仓库、安全策略、通用幂等执行器和统一时钟。 */
    public UserMemoryUseCase(UserMemoryRepository repository,
                             UserMemoryContentPolicy contentPolicy,
                             IdempotentExecutor idempotency, TimeProvider time) {
        this.repository = repository;
        this.contentPolicy = contentPolicy;
        this.idempotency = idempotency;
        this.time = time;
    }

    /** 查询本人记忆开关；尚未创建设置行时返回默认关闭和版本零。 */
    public MemorySettingsView settings(AuthenticatedUser actor) {
        UUID userId = requireActor(actor);
        return repository.findSettings(userId)
                .map(value -> new MemorySettingsView(value.enabled(), value.version()))
                .orElseGet(() -> new MemorySettingsView(false, 0));
    }

    /** 以乐观锁和外部幂等保护修改本人记忆开关。 */
    public MemorySettingsView updateSettings(AuthenticatedUser actor, boolean enabled,
                                             long expectedVersion, String idempotencyKey) {
        UUID userId = requireActor(actor);
        IdempotencyCommand command = command(userId, "MEMORY_SETTINGS_UPDATE", idempotencyKey,
                RequestFingerprint.sha256(Boolean.toString(enabled), Long.toString(expectedVersion)));
        return idempotency.execute(command, () -> {
            UserMemorySettings current = repository.findSettings(userId).orElse(null);
            long actual = current == null ? 0 : current.version();
            if (expectedVersion < 0 || actual != expectedVersion) {
                throw versionConflict();
            }
            Instant now = time.now();
            UserMemorySettings changed = current == null
                    ? new UserMemorySettings(null, userId, enabled, 1, now, now)
                    : new UserMemorySettings(current.id(), userId, enabled,
                    current.version() + 1, current.createdAt(), now);
            UserMemorySettings saved = repository.saveSettings(changed, expectedVersion);
            return new IdempotentResource<>("USER_MEMORY_SETTINGS", saved.id(),
                    new MemorySettingsView(saved.enabled(), saved.version()));
        }, id -> repository.findSettingsById(id).filter(value -> value.userId().equals(userId))
                .map(value -> new MemorySettingsView(value.enabled(), value.version()))
                .orElseThrow(this::notFound));
    }

    /** 分页查询本人记忆，管理员也不能借此读取其他用户正文。 */
    public UserMemoryPage list(AuthenticatedUser actor, MemoryStatus status, int page, int size) {
        UUID userId = requireActor(actor);
        if (page < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("页码必须大于零且每页数量必须在 1 到 100 之间");
        }
        long total = repository.countPage(userId, status);
        List<UserMemoryView> items = repository.findPage(userId, status,
                (page - 1) * size, size).stream().map(UserMemoryView::from).toList();
        int totalPages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        return new UserMemoryPage(items, page, size, total, totalPages);
    }

    /** 确认本人候选，使其成为可以注入上下文的有效记忆。 */
    public UserMemoryView confirm(AuthenticatedUser actor, UUID memoryId,
                                  long expectedVersion, String idempotencyKey) {
        return mutate(actor, memoryId, expectedVersion, idempotencyKey, "MEMORY_CONFIRM",
                current -> {
                    if (current.status() != MemoryStatus.PROPOSED) {
                        throw statusConflict("只有候选记忆可以确认");
                    }
                    Instant now = time.now();
                    if (current.expiresAt() != null && !current.expiresAt().isAfter(now)) {
                        throw statusConflict("已经过期的候选记忆不能确认");
                    }
                    return current.confirm(current.userId().toString(), now);
                }, "confirm");
    }

    /** 更正本人未撤销记忆的正文、过期时间或固定标志。 */
    public UserMemoryView revise(AuthenticatedUser actor, UUID memoryId, String content,
                                 Instant expiresAt, boolean clearExpiresAt, Boolean pinned,
                                 long expectedVersion, String idempotencyKey) {
        if (content == null && expiresAt == null && !clearExpiresAt && pinned == null) {
            throw new IllegalArgumentException("更正请求至少需要修改正文、过期时间或固定标志之一");
        }
        if (expiresAt != null && clearExpiresAt) {
            throw new IllegalArgumentException("expiresAt 与 clearExpiresAt 不能同时设置");
        }
        String fingerprint = RequestFingerprint.sha256(content,
                expiresAt == null ? null : expiresAt.toString(), Boolean.toString(clearExpiresAt),
                pinned == null ? null : pinned.toString(), Long.toString(expectedVersion));
        return mutate(actor, memoryId, expectedVersion, idempotencyKey, "MEMORY_REVISE",
                current -> {
                    NormalizedMemory normalized = content == null
                            ? new NormalizedMemory(current.content(), current.contentHash())
                            : contentPolicy.normalize(content);
                    Instant changedExpiry = clearExpiresAt ? null
                            : expiresAt == null ? current.expiresAt() : expiresAt;
                    if (changedExpiry != null && !changedExpiry.isAfter(time.now())) {
                        throw new IllegalArgumentException("记忆过期时间必须晚于当前时间");
                    }
                    boolean changedPinned = pinned == null ? current.pinned() : pinned;
                    try {
                        return current.revise(normalized.content(), normalized.contentHash(),
                                changedExpiry, changedPinned, current.userId().toString(), time.now());
                    } catch (IllegalStateException exception) {
                        throw statusConflict(exception.getMessage());
                    }
                }, fingerprint);
    }

    /** 撤销本人候选或有效记忆，使其立即停止注入。 */
    public UserMemoryView revoke(AuthenticatedUser actor, UUID memoryId,
                                 long expectedVersion, String idempotencyKey) {
        return mutate(actor, memoryId, expectedVersion, idempotencyKey, "MEMORY_REVOKE",
                current -> {
                    try {
                        return current.revoke(current.userId().toString(), time.now());
                    } catch (IllegalStateException exception) {
                        throw statusConflict(exception.getMessage());
                    }
                }, "revoke");
    }

    /** 以所有者、乐观锁和幂等保护永久删除本人记忆。 */
    public void delete(AuthenticatedUser actor, UUID memoryId,
                       long expectedVersion, String idempotencyKey) {
        UUID userId = requireActor(actor);
        IdempotencyCommand command = command(userId, "MEMORY_DELETE", idempotencyKey,
                RequestFingerprint.sha256(memoryId.toString(), Long.toString(expectedVersion)));
        idempotency.execute(command, () -> {
            UserMemory current = load(userId, memoryId, expectedVersion);
            if (repository.delete(userId, memoryId, expectedVersion) != 1) {
                throw versionConflict();
            }
            return new IdempotentResource<>("USER_MEMORY_DELETED", current.id(), Boolean.TRUE);
        }, ignored -> Boolean.TRUE);
    }

    /** 执行确认、更正或撤销的通用所有者和幂等流程。 */
    private UserMemoryView mutate(AuthenticatedUser actor, UUID memoryId, long expectedVersion,
                                  String idempotencyKey, String operation,
                                  java.util.function.UnaryOperator<UserMemory> change,
                                  String fingerprintValue) {
        UUID userId = requireActor(actor);
        IdempotencyCommand command = command(userId, operation, idempotencyKey,
                RequestFingerprint.sha256(memoryId.toString(), Long.toString(expectedVersion),
                        fingerprintValue));
        return idempotency.execute(command, () -> {
            UserMemory current = load(userId, memoryId, expectedVersion);
            UserMemory saved = repository.update(change.apply(current), expectedVersion);
            return new IdempotentResource<>("USER_MEMORY", saved.id(), UserMemoryView.from(saved));
        }, id -> repository.findById(userId, id).map(UserMemoryView::from)
                .orElseThrow(this::notFound));
    }

    /** 读取所有者资源并执行预期版本检查。 */
    private UserMemory load(UUID userId, UUID memoryId, long expectedVersion) {
        if (memoryId == null || expectedVersion < 0) {
            throw new IllegalArgumentException("记忆 ID 和预期版本不合法");
        }
        UserMemory current = repository.findByMemoryId(userId, memoryId).orElseThrow(this::notFound);
        if (current.version() != expectedVersion) {
            throw versionConflict();
        }
        return current;
    }

    /** 创建当前用户范围内的外部幂等命令。 */
    private IdempotencyCommand command(UUID userId, String operation, String key, String hash) {
        if (key == null || key.isBlank() || key.strip().length() > 160) {
            throw new IllegalArgumentException("Idempotency-Key 必填且不能超过 160 个字符");
        }
        return new IdempotencyCommand(userId.toString(), operation, key.strip(), hash,
                IDEMPOTENCY_LEASE, IDEMPOTENCY_RETENTION);
    }

    /** 返回当前认证用户公开 UUID。 */
    private UUID requireActor(AuthenticatedUser actor) {
        if (actor == null || actor.userId() == null) {
            throw new ApplicationException(ErrorCode.AUTH_UNAUTHORIZED, "认证信息无效或已经过期");
        }
        return actor.userId();
    }

    /** 创建不会泄露资源归属的不存在错误。 */
    private ApplicationException notFound() {
        return new ApplicationException(ErrorCode.MEMORY_NOT_FOUND, "长期记忆不存在");
    }

    /** 创建统一长期记忆乐观锁冲突。 */
    private ApplicationException versionConflict() {
        return new ApplicationException(ErrorCode.MEMORY_VERSION_CONFLICT, "长期记忆版本已变化");
    }

    /** 创建统一长期记忆状态冲突。 */
    private ApplicationException statusConflict(String message) {
        return new ApplicationException(ErrorCode.MEMORY_STATUS_CONFLICT, message);
    }
}
