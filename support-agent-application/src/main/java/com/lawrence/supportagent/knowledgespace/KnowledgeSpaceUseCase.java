package com.lawrence.supportagent.knowledgespace;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.auth.SecurityEventType;
import com.lawrence.supportagent.auth.port.SecurityEventPort;
import com.lawrence.supportagent.auth.port.UserRepository;
import com.lawrence.supportagent.idempotency.IdempotencyCommand;
import com.lawrence.supportagent.idempotency.IdempotentExecutor;
import com.lawrence.supportagent.idempotency.IdempotentResource;
import com.lawrence.supportagent.idempotency.RequestFingerprint;
import com.lawrence.supportagent.knowledgespace.port.KnowledgeSpaceRepository;
import com.lawrence.supportagent.knowledgespace.port.SpaceMembershipRepository;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.sharedkernel.port.UuidGenerator;
import com.lawrence.supportagent.user.UserAccount;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** 编排空间查询、治理、成员变更、幂等、乐观锁和最小安全审计。 */
public class KnowledgeSpaceUseCase {
    private static final Duration IDEMPOTENCY_LEASE = Duration.ofSeconds(30);
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofDays(7);
    private final KnowledgeSpaceRepository spaces;
    private final SpaceMembershipRepository memberships;
    private final UserRepository users;
    private final KnowledgeSpaceAccessService access;
    private final IdempotentExecutor idempotency;
    private final UuidGenerator ids;
    private final TimeProvider time;
    private final SecurityEventPort events;

    /** 注入空间、成员、用户、授权、幂等、标识、时间和审计端口。 */
    public KnowledgeSpaceUseCase(KnowledgeSpaceRepository spaces,
                                 SpaceMembershipRepository memberships,
                                 UserRepository users, KnowledgeSpaceAccessService access,
                                 IdempotentExecutor idempotency, UuidGenerator ids,
                                 TimeProvider time, SecurityEventPort events) {
        this.spaces = spaces;
        this.memberships = memberships;
        this.users = users;
        this.access = access;
        this.idempotency = idempotency;
        this.ids = ids;
        this.time = time;
        this.events = events;
    }

    /** 分页查询当前用户可读活动空间；管理员可使用受控管理筛选。 */
    public KnowledgeSpacePage list(AuthenticatedUser actor, KnowledgeSpaceStatus status,
                                   KnowledgeSpaceVisibility visibility, String keyword,
                                   int page, int size) {
        validatePage(page, size);
        String normalizedKeyword = normalizeKeyword(keyword);
        List<KnowledgeSpace> values;
        long total;
        access.requireActiveAccount(actor);
        if (actor.administrator()) {
            values = spaces.findAdminPage(status, visibility, normalizedKeyword,
                    (page - 1) * size, size);
            total = spaces.countAdminPage(status, visibility, normalizedKeyword);
        } else {
            if (status != null || visibility != null || normalizedKeyword != null) {
                throw new ApplicationException(ErrorCode.AUTH_FORBIDDEN,
                        "普通用户不能使用管理筛选条件");
            }
            values = spaces.findReadable(actor.userId(), (page - 1) * size, size);
            total = spaces.countReadable(actor.userId());
        }
        return new KnowledgeSpacePage(values.stream().map(value -> view(actor, value)).toList(),
                page, size, total);
    }

    /** 查询可见空间详情；无权受限空间统一返回不存在。 */
    public KnowledgeSpaceView details(AuthenticatedUser actor, UUID spaceId) {
        return view(actor, access.requireReadable(actor, requireSpaceId(spaceId)));
    }

    /** 平台管理员幂等创建默认受限空间。 */
    public KnowledgeSpaceView create(AuthenticatedUser actor, String code, String name,
                                     String description, String idempotencyKey) {
        requireAdmin(actor);
        String normalizedCode = KnowledgeSpace.normalizeCode(code);
        IdempotencyCommand command = command(actor, "KNOWLEDGE_SPACE_CREATE", idempotencyKey,
                RequestFingerprint.sha256(normalizedCode, name, description));
        return idempotency.execute(command, () -> {
            if (spaces.findByCode(normalizedCode).isPresent()) {
                throw new ApplicationException(ErrorCode.KNOWLEDGE_SPACE_CODE_CONFLICT,
                        "空间代码已经存在");
            }
            KnowledgeSpace saved = spaces.save(KnowledgeSpace.create(ids.generate(), normalizedCode,
                    name, description, actor.userId().toString(), time.now()));
            audit(SecurityEventType.SPACE_CREATED, null, saved, actor, "CREATED");
            return new IdempotentResource<>("KNOWLEDGE_SPACE", saved.id(), view(actor, saved));
        }, id -> replaySpace(actor, id));
    }

    /** 平台管理员按版本幂等修改普通空间展示信息与可见性。 */
    public KnowledgeSpaceView update(AuthenticatedUser actor, UUID spaceId, String name,
                                     String description, KnowledgeSpaceVisibility visibility,
                                     long expectedVersion, String idempotencyKey) {
        requireAdmin(actor);
        UUID requiredId = requireSpaceId(spaceId);
        IdempotencyCommand command = command(actor, "KNOWLEDGE_SPACE_UPDATE", idempotencyKey,
                RequestFingerprint.sha256(requiredId.toString(), name, description,
                        visibility == null ? null : visibility.name(), Long.toString(expectedVersion)));
        return idempotency.execute(command, () -> {
            KnowledgeSpace current = requireVersion(requiredId, expectedVersion);
            requireNonGlobal(current);
            KnowledgeSpace saved = spaces.save(current.revise(name, description, visibility,
                    actor.userId().toString(), time.now()));
            audit(SecurityEventType.SPACE_UPDATED, null, saved, actor, "UPDATED");
            return new IdempotentResource<>("KNOWLEDGE_SPACE", saved.id(), view(actor, saved));
        }, id -> replaySpace(actor, id));
    }

    /** 平台管理员按版本幂等停用普通空间。 */
    public KnowledgeSpaceView disable(AuthenticatedUser actor, UUID spaceId, long expectedVersion,
                                      String idempotencyKey) {
        return changeStatus(actor, spaceId, expectedVersion, idempotencyKey, false);
    }

    /** 平台管理员按版本幂等重新启用普通空间。 */
    public KnowledgeSpaceView enable(AuthenticatedUser actor, UUID spaceId, long expectedVersion,
                                     String idempotencyKey) {
        return changeStatus(actor, spaceId, expectedVersion, idempotencyKey, true);
    }

    /** 分页查询空间成员；只返回成员所需的最小账号摘要。 */
    public SpaceMembershipPage members(AuthenticatedUser actor, UUID spaceId, int page, int size) {
        validatePage(page, size);
        KnowledgeSpace space = access.requireRoleForMetadata(actor, requireSpaceId(spaceId),
                SpaceRole.MANAGER);
        List<SpaceMembershipView> items = memberships.findPage(space.spaceId(),
                        (page - 1) * size, size).stream().map(this::membershipView).toList();
        return new SpaceMembershipPage(items, page, size,
                memberships.countPage(space.spaceId()));
    }

    /** 新增、恢复或变更空间成员角色，并复用同一唯一关系记录。 */
    public SpaceMembershipView putMember(AuthenticatedUser actor, UUID spaceId, UUID userId,
                                         SpaceRole role, Long expectedVersion,
                                         String idempotencyKey) {
        UUID requiredSpaceId = requireSpaceId(spaceId);
        access.requireRole(actor, requiredSpaceId, SpaceRole.MANAGER);
        UUID requiredUserId = requireUser(userId).userId();
        if (role == null) {
            throw new IllegalArgumentException("空间角色不能为空");
        }
        if (KnowledgeSpace.GLOBAL_SPACE_ID.equals(requiredSpaceId) && role == SpaceRole.READER) {
            throw new ApplicationException(ErrorCode.KNOWLEDGE_SPACE_MEMBERSHIP_CONFLICT,
                    "GLOBAL 不保存隐式读取成员关系");
        }
        IdempotencyCommand command = command(actor, "SPACE_MEMBERSHIP_PUT", idempotencyKey,
                RequestFingerprint.sha256(requiredSpaceId.toString(), requiredUserId.toString(),
                        role.name(), expectedVersion == null ? null : expectedVersion.toString()));
        return idempotency.execute(command, () -> putMemberOnce(actor, requiredSpaceId,
                requiredUserId, role, expectedVersion), this::replayMembership);
    }

    /** 按版本撤销成员关系，且不允许受限空间失去最后一个活动 MANAGER。 */
    public SpaceMembershipView revokeMember(AuthenticatedUser actor, UUID spaceId, UUID userId,
                                            long expectedVersion, String idempotencyKey) {
        UUID requiredSpaceId = requireSpaceId(spaceId);
        access.requireRole(actor, requiredSpaceId, SpaceRole.MANAGER);
        UUID requiredUserId = requireUser(userId).userId();
        IdempotencyCommand command = command(actor, "SPACE_MEMBERSHIP_REVOKE", idempotencyKey,
                RequestFingerprint.sha256(requiredSpaceId.toString(), requiredUserId.toString(),
                        Long.toString(expectedVersion)));
        return idempotency.execute(command, () -> {
            KnowledgeSpace space = spaces.findBySpaceIdForUpdate(requiredSpaceId)
                    .orElseThrow(this::spaceNotFound);
            SpaceMembership current = requireMembership(requiredUserId, requiredSpaceId,
                    expectedVersion);
            protectLastManager(space, current, null);
            SpaceMembership saved = memberships.save(current.revoke(
                    actor.userId().toString(), time.now()));
            auditMembership(SecurityEventType.SPACE_MEMBER_REVOKED, saved, actor, "REVOKED");
            return new IdempotentResource<>("SPACE_MEMBERSHIP", saved.id(),
                    membershipView(saved));
        }, this::replayMembership);
    }

    /** 执行一次成员新增、恢复或改角色。 */
    private IdempotentResource<SpaceMembershipView> putMemberOnce(
            AuthenticatedUser actor, UUID spaceId, UUID userId, SpaceRole role,
            Long expectedVersion) {
        KnowledgeSpace space = spaces.findBySpaceIdForUpdate(spaceId).orElseThrow(this::spaceNotFound);
        SpaceMembership existing = memberships.find(userId, spaceId).orElse(null);
        SpaceMembership saved;
        SecurityEventType eventType;
        if (existing == null) {
            if (expectedVersion != null) {
                throw versionConflict();
            }
            saved = memberships.save(SpaceMembership.create(userId, spaceId, role,
                    actor.userId().toString(), time.now()));
            eventType = SecurityEventType.SPACE_MEMBER_ADDED;
        } else {
            if (expectedVersion == null || existing.version() != expectedVersion) {
                throw versionConflict();
            }
            protectLastManager(space, existing, role);
            if (existing.status() == SpaceMembershipStatus.REVOKED) {
                saved = memberships.save(existing.restore(role, actor.userId().toString(), time.now()));
                eventType = SecurityEventType.SPACE_MEMBER_RESTORED;
            } else if (existing.role() != role) {
                saved = memberships.save(existing.changeRole(role, actor.userId().toString(), time.now()));
                eventType = SecurityEventType.SPACE_MEMBER_ROLE_CHANGED;
            } else {
                throw new ApplicationException(ErrorCode.KNOWLEDGE_SPACE_MEMBERSHIP_CONFLICT,
                        "成员已经具有目标角色");
            }
        }
        auditMembership(eventType, saved, actor, role.name());
        return new IdempotentResource<>("SPACE_MEMBERSHIP", saved.id(), membershipView(saved));
    }

    /** 幂等地切换空间活动状态。 */
    private KnowledgeSpaceView changeStatus(AuthenticatedUser actor, UUID spaceId,
                                            long expectedVersion, String idempotencyKey,
                                            boolean enable) {
        requireAdmin(actor);
        UUID requiredId = requireSpaceId(spaceId);
        String operation = enable ? "KNOWLEDGE_SPACE_ENABLE" : "KNOWLEDGE_SPACE_DISABLE";
        IdempotencyCommand command = command(actor, operation, idempotencyKey,
                RequestFingerprint.sha256(requiredId.toString(), Long.toString(expectedVersion)));
        return idempotency.execute(command, () -> {
            KnowledgeSpace current = requireVersion(requiredId, expectedVersion);
            requireNonGlobal(current);
            KnowledgeSpace saved;
            try {
                saved = spaces.save(enable
                        ? current.enable(actor.userId().toString(), time.now())
                        : current.disable(actor.userId().toString(), time.now()));
            } catch (IllegalStateException exception) {
                throw new ApplicationException(ErrorCode.KNOWLEDGE_SPACE_DISABLED,
                        "知识空间状态不允许当前操作");
            }
            audit(enable ? SecurityEventType.SPACE_ENABLED : SecurityEventType.SPACE_DISABLED,
                    null, saved, actor, enable ? "ENABLED" : "DISABLED");
            return new IdempotentResource<>("KNOWLEDGE_SPACE", saved.id(), view(actor, saved));
        }, id -> replaySpace(actor, id));
    }

    /** 防止受限空间的最后一个活动 MANAGER 被撤销或降级。 */
    private void protectLastManager(KnowledgeSpace space, SpaceMembership current,
                                    SpaceRole targetRole) {
        if (space.visibility() == KnowledgeSpaceVisibility.RESTRICTED
                && current.status() == SpaceMembershipStatus.ACTIVE
                && current.role() == SpaceRole.MANAGER && targetRole != SpaceRole.MANAGER
                && memberships.countActiveManagers(space.spaceId()) <= 1) {
            throw new ApplicationException(ErrorCode.KNOWLEDGE_SPACE_MEMBERSHIP_CONFLICT,
                    "受限空间必须至少保留一个活动 MANAGER");
        }
    }

    /** 加载空间并校验期望版本。 */
    private KnowledgeSpace requireVersion(UUID spaceId, long expectedVersion) {
        KnowledgeSpace current = spaces.findBySpaceId(spaceId).orElseThrow(this::spaceNotFound);
        if (expectedVersion < 0 || current.version() != expectedVersion) {
            throw versionConflict();
        }
        return current;
    }

    /** 加载成员并校验期望版本。 */
    private SpaceMembership requireMembership(UUID userId, UUID spaceId, long expectedVersion) {
        SpaceMembership current = memberships.find(userId, spaceId).orElseThrow(() ->
                new ApplicationException(ErrorCode.KNOWLEDGE_SPACE_MEMBERSHIP_CONFLICT,
                        "成员关系不存在"));
        if (expectedVersion < 0 || current.version() != expectedVersion) {
            throw versionConflict();
        }
        return current;
    }

    /** 读取成员关联的最小账号摘要。 */
    private SpaceMembershipView membershipView(SpaceMembership value) {
        UserAccount user = users.findByUserId(value.userId()).orElseThrow(() ->
                new ApplicationException(ErrorCode.AUTH_USER_NOT_FOUND, "用户不存在"));
        return new SpaceMembershipView(value.userId(), user.username(), user.displayName(),
                value.role(), value.status(), value.version(), value.createdAt(),
                value.updatedAt(), value.revokedAt());
    }

    /** 返回空间视图并附加当前用户显式角色。 */
    private KnowledgeSpaceView view(AuthenticatedUser actor, KnowledgeSpace value) {
        return KnowledgeSpaceView.from(value, access.currentRole(actor, value.spaceId()));
    }

    /** 按内部主键重放空间写操作结果。 */
    private KnowledgeSpaceView replaySpace(AuthenticatedUser actor, long id) {
        KnowledgeSpace value = spaces.findById(id).orElseThrow(this::spaceNotFound);
        return view(actor, value);
    }

    /** 按内部主键重放成员写操作结果。 */
    private SpaceMembershipView replayMembership(long id) {
        return memberships.findMembershipById(id).map(this::membershipView).orElseThrow(() ->
                new ApplicationException(ErrorCode.KNOWLEDGE_SPACE_MEMBERSHIP_CONFLICT,
                        "成员关系不存在"));
    }

    /** 记录空间资源安全事件。 */
    private void audit(SecurityEventType type, UUID targetUserId, KnowledgeSpace space,
                       AuthenticatedUser actor, String reason) {
        events.recordResource(type, targetUserId, "KNOWLEDGE_SPACE", space.spaceId().toString(),
                actor.userId().toString(), "SUCCEEDED", reason, time.now());
    }

    /** 记录成员资源安全事件并保留目标用户 UUID。 */
    private void auditMembership(SecurityEventType type, SpaceMembership membership,
                                 AuthenticatedUser actor, String reason) {
        events.recordResource(type, membership.userId(), "SPACE_MEMBERSHIP",
                membership.spaceId().toString(), actor.userId().toString(),
                "SUCCEEDED", reason, time.now());
    }

    /** 构造统一外部幂等命令。 */
    private IdempotencyCommand command(AuthenticatedUser actor, String operation,
                                       String key, String fingerprint) {
        return new IdempotencyCommand(actor.userId().toString(), operation,
                requireIdempotencyKey(key), fingerprint, IDEMPOTENCY_LEASE,
                IDEMPOTENCY_RETENTION);
    }

    /** 校验平台管理员身份。 */
    private void requireAdmin(AuthenticatedUser actor) {
        if (actor == null || !actor.administrator()) {
            throw new ApplicationException(ErrorCode.AUTH_FORBIDDEN, "当前用户没有管理员权限");
        }
        access.requireActiveAccount(actor);
    }

    /** 校验目标用户存在。 */
    private UserAccount requireUser(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("用户标识不能为空");
        }
        return users.findByUserId(userId).orElseThrow(() ->
                new ApplicationException(ErrorCode.AUTH_USER_NOT_FOUND, "用户不存在"));
    }

    /** 拒绝修改固定 GLOBAL 系统空间。 */
    private void requireNonGlobal(KnowledgeSpace space) {
        if (space.systemSpace()) {
            throw new ApplicationException(ErrorCode.KNOWLEDGE_SPACE_GLOBAL_IMMUTABLE,
                    "GLOBAL 系统空间不可修改");
        }
    }

    /** 校验 UUID 路径参数。 */
    private UUID requireSpaceId(UUID spaceId) {
        if (spaceId == null) {
            throw new IllegalArgumentException("空间标识不能为空");
        }
        return spaceId;
    }

    /** 校验分页范围并防止整数溢出。 */
    private void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > 100 || page - 1 > Integer.MAX_VALUE / size) {
            throw new IllegalArgumentException("页码不能小于 1，每页数量必须为 1 至 100");
        }
    }

    /** 规范化可空管理关键字。 */
    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String normalized = keyword.strip();
        if (normalized.length() > 100) {
            throw new IllegalArgumentException("空间关键字不能超过 100 个字符");
        }
        return normalized;
    }

    /** 校验客户端幂等键。 */
    private String requireIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("幂等键不能为空");
        }
        String normalized = value.strip();
        if (normalized.length() > 160) {
            throw new IllegalArgumentException("幂等键不能超过 160 个字符");
        }
        return normalized;
    }

    /** 创建统一空间不存在异常。 */
    private ApplicationException spaceNotFound() {
        return new ApplicationException(ErrorCode.KNOWLEDGE_SPACE_NOT_FOUND, "知识空间不存在");
    }

    /** 创建统一空间或成员版本冲突异常。 */
    private ApplicationException versionConflict() {
        return new ApplicationException(ErrorCode.KNOWLEDGE_SPACE_VERSION_CONFLICT,
                "空间或成员版本已变化");
    }
}
