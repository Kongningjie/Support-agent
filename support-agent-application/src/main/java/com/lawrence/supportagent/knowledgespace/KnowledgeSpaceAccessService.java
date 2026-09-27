package com.lawrence.supportagent.knowledgespace;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.auth.port.UserRepository;
import com.lawrence.supportagent.knowledgespace.port.KnowledgeSpaceRepository;
import com.lawrence.supportagent.knowledgespace.port.SpaceMembershipRepository;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.user.UserAccount;
import com.lawrence.supportagent.user.UserStatus;
import java.util.Optional;
import java.util.UUID;

/** 统一执行账号、空间可见性、成员状态和角色的应用层访问判定。 */
public class KnowledgeSpaceAccessService {
    private final KnowledgeSpaceRepository spaces;
    private final SpaceMembershipRepository memberships;
    private final UserRepository users;

    /** 注入空间、成员和账号事实端口。 */
    public KnowledgeSpaceAccessService(KnowledgeSpaceRepository spaces,
                                       SpaceMembershipRepository memberships,
                                       UserRepository users) {
        this.spaces = spaces;
        this.memberships = memberships;
        this.users = users;
    }

    /** 返回调用者可见的空间；完全无权的受限空间与不存在统一为 404。 */
    public KnowledgeSpace requireReadable(AuthenticatedUser actor, UUID spaceId) {
        UserAccount account = requireActiveActor(actor);
        KnowledgeSpace space = spaces.findBySpaceId(spaceId).orElseThrow(this::notFound);
        Optional<SpaceMembership> membership = activeMembership(account.userId(), spaceId);
        if (actor.administrator()) {
            return space;
        }
        if (space.status() == KnowledgeSpaceStatus.DISABLED) {
            if (membership.isPresent()) {
                return space;
            }
            throw notFound();
        }
        if (space.visibility() == KnowledgeSpaceVisibility.ENTERPRISE || membership.isPresent()) {
            return space;
        }
        throw notFound();
    }

    /** 要求调用者具有指定空间角色，平台管理员直接满足但仍受空间状态约束。 */
    public KnowledgeSpace requireRole(AuthenticatedUser actor, UUID spaceId, SpaceRole required) {
        KnowledgeSpace space = requireReadable(actor, spaceId);
        if (space.status() != KnowledgeSpaceStatus.ACTIVE) {
            throw new ApplicationException(ErrorCode.KNOWLEDGE_SPACE_DISABLED, "知识空间已停用");
        }
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
}
