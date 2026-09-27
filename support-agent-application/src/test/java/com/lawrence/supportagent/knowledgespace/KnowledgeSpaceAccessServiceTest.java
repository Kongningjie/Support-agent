package com.lawrence.supportagent.knowledgespace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.auth.port.UserRepository;
import com.lawrence.supportagent.knowledgespace.port.KnowledgeSpaceRepository;
import com.lawrence.supportagent.knowledgespace.port.SpaceMembershipRepository;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.user.UserAccount;
import com.lawrence.supportagent.user.UserRole;
import com.lawrence.supportagent.user.UserStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 验证企业可读、受限枚举保护、停用元数据和角色授权。 */
class KnowledgeSpaceAccessServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");
    private static final UUID USER_ID = UUID.fromString("20000000-0000-0000-0000-000000000018");
    private static final UUID SPACE_ID = UUID.fromString("30000000-0000-0000-0000-000000000018");
    private final AuthenticatedUser actor = new AuthenticatedUser(USER_ID, "member", UserRole.USER);
    private KnowledgeSpaceRepository spaces;
    private SpaceMembershipRepository memberships;
    private UserRepository users;
    private KnowledgeSpaceAccessService service;

    /** 创建隔离的端口替身和活动用户。 */
    @BeforeEach
    void setUp() {
        spaces = mock(KnowledgeSpaceRepository.class);
        memberships = mock(SpaceMembershipRepository.class);
        users = mock(UserRepository.class);
        service = new KnowledgeSpaceAccessService(spaces, memberships, users);
        when(users.findByUserId(USER_ID)).thenReturn(Optional.of(activeUser()));
    }

    /** 企业活动空间无需显式成员关系即可读取。 */
    @Test
    void shouldReadActiveEnterpriseSpaceImplicitly() {
        KnowledgeSpace enterprise = space(KnowledgeSpaceVisibility.ENTERPRISE,
                KnowledgeSpaceStatus.ACTIVE);
        when(spaces.findBySpaceId(SPACE_ID)).thenReturn(Optional.of(enterprise));
        when(memberships.find(USER_ID, SPACE_ID)).thenReturn(Optional.empty());

        assertEquals(enterprise, service.requireReadable(actor, SPACE_ID));
    }

    /** 无权受限空间必须与不存在统一表现为 404。 */
    @Test
    void shouldHideRestrictedSpaceFromNonMember() {
        when(spaces.findBySpaceId(SPACE_ID)).thenReturn(Optional.of(space(
                KnowledgeSpaceVisibility.RESTRICTED, KnowledgeSpaceStatus.ACTIVE)));
        when(memberships.find(USER_ID, SPACE_ID)).thenReturn(Optional.empty());

        ApplicationException exception = assertThrows(ApplicationException.class,
                () -> service.requireReadable(actor, SPACE_ID));

        assertEquals(ErrorCode.KNOWLEDGE_SPACE_NOT_FOUND, exception.errorCode());
    }

    /** 已停用空间允许原 MANAGER 读取治理历史，但拒绝任何新写入授权。 */
    @Test
    void shouldAllowDisabledMetadataButRejectWrites() {
        when(spaces.findBySpaceId(SPACE_ID)).thenReturn(Optional.of(space(
                KnowledgeSpaceVisibility.RESTRICTED, KnowledgeSpaceStatus.DISABLED)));
        when(memberships.find(USER_ID, SPACE_ID)).thenReturn(Optional.of(membership()));

        service.requireRoleForMetadata(actor, SPACE_ID, SpaceRole.MANAGER);
        ApplicationException exception = assertThrows(ApplicationException.class,
                () -> service.requireRole(actor, SPACE_ID, SpaceRole.READER));

        assertEquals(ErrorCode.KNOWLEDGE_SPACE_DISABLED, exception.errorCode());
    }

    /** 创建测试空间快照。 */
    private KnowledgeSpace space(KnowledgeSpaceVisibility visibility,
                                 KnowledgeSpaceStatus status) {
        return new KnowledgeSpace(18L, SPACE_ID, "TEAM_OPS", "运维知识", null,
                visibility, status, false, 0, "admin", NOW, "admin", NOW);
    }

    /** 创建活动 MANAGER 成员关系。 */
    private SpaceMembership membership() {
        return new SpaceMembership(18L, USER_ID, SPACE_ID, SpaceRole.MANAGER,
                SpaceMembershipStatus.ACTIVE, 0, "admin", NOW, "admin", NOW,
                null, null);
    }

    /** 创建活动本地用户。 */
    private UserAccount activeUser() {
        return new UserAccount(18L, USER_ID, "member", "成员", "hash", UserRole.USER,
                UserStatus.ACTIVE, 0, NOW, false, null, "admin", NOW, "admin", NOW);
    }
}
