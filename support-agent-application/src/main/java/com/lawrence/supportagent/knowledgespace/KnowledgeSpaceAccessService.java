package com.lawrence.supportagent.knowledgespace;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.auth.SecurityEventType;
import com.lawrence.supportagent.auth.port.SecurityEventPort;
import com.lawrence.supportagent.auth.port.UserRepository;
import com.lawrence.supportagent.knowledgespace.port.KnowledgeSpaceRepository;
import com.lawrence.supportagent.knowledgespace.port.SpaceMembershipRepository;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.retrieval.RetrievalAccessContext;
import com.lawrence.supportagent.user.UserAccount;
import com.lawrence.supportagent.user.UserStatus;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 统一执行账号、空间可见性、成员状态和角色的应用层访问判定。 */
public class KnowledgeSpaceAccessService {
    private final KnowledgeSpaceRepository spaces;
    private final SpaceMembershipRepository memberships;
    private final UserRepository users;
    private final KnowledgeSpaceTelemetryPort telemetry;
    private final SecurityEventPort events;
    private final TimeProvider time;

    /** 注入空间、成员和账号事实端口。 */
    public KnowledgeSpaceAccessService(KnowledgeSpaceRepository spaces,
                                       SpaceMembershipRepository memberships,
                                       UserRepository users) {
        this(spaces, memberships, users, KnowledgeSpaceTelemetryPort.noOp(), null, null);
    }

    /** 注入事实仓储、低基数遥测与最小安全审计端口。 */
    public KnowledgeSpaceAccessService(KnowledgeSpaceRepository spaces,
                                       SpaceMembershipRepository memberships,
                                       UserRepository users,
                                       KnowledgeSpaceTelemetryPort telemetry,
                                       SecurityEventPort events,
                                       TimeProvider time) {
        this.spaces = spaces;
        this.memberships = memberships;
        this.users = users;
        this.telemetry = telemetry == null ? KnowledgeSpaceTelemetryPort.noOp() : telemetry;
        this.events = events;
        this.time = time;
    }

    /** 返回调用者可见的空间；完全无权的受限空间与不存在统一为 404。 */
    public KnowledgeSpace requireReadable(AuthenticatedUser actor, UUID spaceId) {
        KnowledgeSpace space = null;
        try {
            UserAccount account = requireActiveActor(actor);
            space = spaces.findBySpaceId(spaceId).orElseThrow(this::notFound);
            Optional<SpaceMembership> membership = activeMembership(account.userId(), spaceId);
            if (actor.administrator()) {
                recordDecision(actor, space, KnowledgeSpaceTelemetryPort.Action.READ,
                        KnowledgeSpaceTelemetryPort.Result.ALLOWED,
                        KnowledgeSpaceTelemetryPort.RequiredRole.READ, "ALLOWED");
                return space;
            }
            if (space.status() == KnowledgeSpaceStatus.DISABLED) {
                if (membership.isPresent()) {
                    recordDecision(actor, space, KnowledgeSpaceTelemetryPort.Action.READ,
                            KnowledgeSpaceTelemetryPort.Result.ALLOWED,
                            KnowledgeSpaceTelemetryPort.RequiredRole.READ, "METADATA_ONLY");
                    return space;
                }
                throw notFound();
            }
            if (space.visibility() == KnowledgeSpaceVisibility.ENTERPRISE || membership.isPresent()) {
                recordDecision(actor, space, KnowledgeSpaceTelemetryPort.Action.READ,
                        KnowledgeSpaceTelemetryPort.Result.ALLOWED,
                        KnowledgeSpaceTelemetryPort.RequiredRole.READ, "ALLOWED");
                return space;
            }
            throw notFound();
        } catch (ApplicationException exception) {
            recordDecision(actor, space, KnowledgeSpaceTelemetryPort.Action.READ,
                    KnowledgeSpaceTelemetryPort.Result.DENIED,
                    KnowledgeSpaceTelemetryPort.RequiredRole.READ, reason(exception));
            throw exception;
        }
    }

    /** 要求调用者具有指定空间角色，平台管理员直接满足但仍受空间状态约束。 */
    public KnowledgeSpace requireRole(AuthenticatedUser actor, UUID spaceId, SpaceRole required) {
        KnowledgeSpace space = requireReadable(actor, spaceId);
        if (space.status() != KnowledgeSpaceStatus.ACTIVE) {
            recordDecision(actor, space, KnowledgeSpaceTelemetryPort.Action.ROLE_CHECK,
                    KnowledgeSpaceTelemetryPort.Result.DENIED, requiredRole(required),
                    "SPACE_DISABLED");
            throw new ApplicationException(ErrorCode.KNOWLEDGE_SPACE_DISABLED, "知识空间已停用");
        }
        if (actor.administrator()) {
            recordDecision(actor, space, KnowledgeSpaceTelemetryPort.Action.ROLE_CHECK,
                    KnowledgeSpaceTelemetryPort.Result.ALLOWED, requiredRole(required), "ALLOWED");
            return space;
        }
        SpaceMembership membership = activeMembership(actor.userId(), spaceId).orElse(null);
        if (membership == null) {
            recordDecision(actor, space, KnowledgeSpaceTelemetryPort.Action.ROLE_CHECK,
                    KnowledgeSpaceTelemetryPort.Result.DENIED, requiredRole(required),
                    "MEMBERSHIP_MISSING");
            throw new ApplicationException(
                    ErrorCode.KNOWLEDGE_SPACE_ROLE_REQUIRED, "当前操作需要空间角色");
        }
        if (!membership.role().includes(required)) {
            recordDecision(actor, space, KnowledgeSpaceTelemetryPort.Action.ROLE_CHECK,
                    KnowledgeSpaceTelemetryPort.Result.DENIED, requiredRole(required),
                    "ROLE_INSUFFICIENT");
            throw new ApplicationException(ErrorCode.KNOWLEDGE_SPACE_ROLE_REQUIRED,
                    "当前操作需要更高的空间角色");
        }
        recordDecision(actor, space, KnowledgeSpaceTelemetryPort.Action.ROLE_CHECK,
                KnowledgeSpaceTelemetryPort.Result.ALLOWED, requiredRole(required), "ALLOWED");
        return space;
    }

    /** 要求空间处于活动状态且调用者具有读取权限。 */
    public KnowledgeSpace requireActiveReadable(AuthenticatedUser actor, UUID spaceId) {
        KnowledgeSpace space = requireReadable(actor, spaceId);
        if (space.status() != KnowledgeSpaceStatus.ACTIVE) {
            recordDecision(actor, space, KnowledgeSpaceTelemetryPort.Action.READ,
                    KnowledgeSpaceTelemetryPort.Result.DENIED,
                    KnowledgeSpaceTelemetryPort.RequiredRole.READ, "SPACE_DISABLED");
            throw new ApplicationException(ErrorCode.KNOWLEDGE_SPACE_DISABLED, "知识空间已停用");
        }
        return space;
    }

    /** 构造普通问答固定使用的 GLOBAL 与当前活动空间访问上下文。 */
    public RetrievalAccessContext retrievalContext(AuthenticatedUser actor, UUID activeSpaceId) {
        KnowledgeSpace active = requireActiveReadable(actor, activeSpaceId);
        Set<UUID> allowed = new LinkedHashSet<>();
        allowed.add(KnowledgeSpace.GLOBAL_SPACE_ID);
        allowed.add(active.spaceId());
        telemetry.recordAccess(KnowledgeSpaceTelemetryPort.Action.RETRIEVAL_CONTEXT,
                KnowledgeSpaceTelemetryPort.Result.ALLOWED,
                KnowledgeSpaceTelemetryPort.RequiredRole.READ,
                visibility(active), active.systemSpace(), "ALLOWED");
        telemetry.recordRetrievalScope(allowed.size() == 1
                ? KnowledgeSpaceTelemetryPort.AllowedSpaceCount.ONE
                : KnowledgeSpaceTelemetryPort.AllowedSpaceCount.TWO);
        return new RetrievalAccessContext(actor, active.spaceId(), allowed);
    }

    /** 返回当前调用者可读的全部活动空间 UUID，仅用于资源列表授权过滤。 */
    public Set<UUID> readableActiveSpaceIds(AuthenticatedUser actor) {
        requireActiveActor(actor);
        List<KnowledgeSpace> readable = actor.administrator()
                ? spaces.findAdminPage(KnowledgeSpaceStatus.ACTIVE, null, null, 0, 10_000)
                : spaces.findReadable(actor.userId(), 0, 10_000);
        return readable.stream().map(KnowledgeSpace::spaceId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** 要求调用者具有指定角色，但允许活动成员读取已停用空间的治理历史。 */
    public KnowledgeSpace requireRoleForMetadata(AuthenticatedUser actor, UUID spaceId,
                                                  SpaceRole required) {
        KnowledgeSpace space = requireReadable(actor, spaceId);
        if (actor.administrator()) {
            return space;
        }
        SpaceMembership membership = activeMembership(actor.userId(), spaceId)
                .orElseThrow(() -> new ApplicationException(
                        ErrorCode.KNOWLEDGE_SPACE_ROLE_REQUIRED, "当前操作需要空间角色"));
        if (!membership.role().includes(required)) {
            throw new ApplicationException(ErrorCode.KNOWLEDGE_SPACE_ROLE_REQUIRED,
                    "当前操作需要更高的空间角色");
        }
        return space;
    }

    /** 校验认证主体仍对应 MySQL 中的活动账号。 */
    public UserAccount requireActiveAccount(AuthenticatedUser actor) {
        return requireActiveActor(actor);
    }

    /** 返回调用者在空间中的活动显式角色；隐式企业读取返回空。 */
    public SpaceRole currentRole(AuthenticatedUser actor, UUID spaceId) {
        if (actor == null) {
            return null;
        }
        return activeMembership(actor.userId(), spaceId).map(SpaceMembership::role).orElse(null);
    }

    /** 查询可空活动成员关系。 */
    private Optional<SpaceMembership> activeMembership(UUID userId, UUID spaceId) {
        return memberships.find(userId, spaceId)
                .filter(value -> value.status() == SpaceMembershipStatus.ACTIVE);
    }

    /** 要求认证主体仍对应 MySQL 中的活动账号。 */
    private UserAccount requireActiveActor(AuthenticatedUser actor) {
        if (actor == null) {
            throw new ApplicationException(ErrorCode.AUTH_UNAUTHORIZED, "认证信息无效或已经过期");
        }
        UserAccount account = users.findByUserId(actor.userId()).orElseThrow(() ->
                new ApplicationException(ErrorCode.AUTH_UNAUTHORIZED, "认证信息无效或已经过期"));
        if (account.status() != UserStatus.ACTIVE) {
            throw new ApplicationException(ErrorCode.AUTH_FORBIDDEN, "当前账号已停用");
        }
        return account;
    }

    /** 创建不泄露受限空间存在性的统一异常。 */
    private ApplicationException notFound() {
        return new ApplicationException(ErrorCode.KNOWLEDGE_SPACE_NOT_FOUND, "知识空间不存在");
    }

    /** 将空间角色转换为固定指标标签。 */
    private KnowledgeSpaceTelemetryPort.RequiredRole requiredRole(SpaceRole role) {
        return KnowledgeSpaceTelemetryPort.RequiredRole.valueOf(role.name());
    }

    /** 将空间可见性转换为不含业务标识的固定指标标签。 */
    private KnowledgeSpaceTelemetryPort.Visibility visibility(KnowledgeSpace space) {
        if (space == null) {
            return KnowledgeSpaceTelemetryPort.Visibility.UNKNOWN;
        }
        return KnowledgeSpaceTelemetryPort.Visibility.valueOf(space.visibility().name());
    }

    /** 把公开错误折叠为冻结低基数拒绝原因。 */
    private String reason(ApplicationException exception) {
        return switch (exception.errorCode()) {
            case AUTH_UNAUTHORIZED, AUTH_FORBIDDEN -> "ACCOUNT_INACTIVE";
            case KNOWLEDGE_SPACE_DISABLED -> "SPACE_DISABLED";
            case KNOWLEDGE_SPACE_ROLE_REQUIRED -> "ROLE_INSUFFICIENT";
            default -> "SPACE_NOT_FOUND";
        };
    }

    /** 同时记录低基数指标和最小安全审计，不写空间名称、代码或正文。 */
    private void recordDecision(AuthenticatedUser actor, KnowledgeSpace space,
                                KnowledgeSpaceTelemetryPort.Action action,
                                KnowledgeSpaceTelemetryPort.Result result,
                                KnowledgeSpaceTelemetryPort.RequiredRole requiredRole,
                                String reason) {
        telemetry.recordAccess(action, result, requiredRole, visibility(space),
                space != null && space.systemSpace(), reason);
        if (events != null && time != null && actor != null) {
            events.recordResource(SecurityEventType.SPACE_ACCESS_DECISION, actor.userId(),
                    "KNOWLEDGE_SPACE", space == null ? null : space.spaceId().toString(),
                    actor.userId().toString(), auditResult(result), reason, time.now());
        }
    }

    /** 将指标使用的允许/拒绝枚举转换为安全事件表冻结的结果值。 */
    private String auditResult(KnowledgeSpaceTelemetryPort.Result result) {
        return result == KnowledgeSpaceTelemetryPort.Result.ALLOWED ? "SUCCEEDED" : "DENIED";
    }
}
