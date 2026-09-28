package com.lawrence.supportagent.knowledgespace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.auth.SecurityEventType;
import com.lawrence.supportagent.auth.port.SecurityEventPort;
import com.lawrence.supportagent.auth.port.UserRepository;
import com.lawrence.supportagent.knowledgespace.port.KnowledgeSpaceRepository;
import com.lawrence.supportagent.knowledgespace.port.SpaceMembershipRepository;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.user.UserAccount;
import com.lawrence.supportagent.user.UserRole;
import com.lawrence.supportagent.user.UserStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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

    /** 允许判定写入审计时必须使用数据库冻结的 SUCCEEDED，而不是指标标签 ALLOWED。 */
    @Test
    void shouldMapAllowedMetricResultToSucceededAuditResult() {
        SecurityEventPort events = mock(SecurityEventPort.class);
        TimeProvider time = mock(TimeProvider.class);
        KnowledgeSpace enterprise = space(KnowledgeSpaceVisibility.ENTERPRISE,
                KnowledgeSpaceStatus.ACTIVE);
        when(spaces.findBySpaceId(SPACE_ID)).thenReturn(Optional.of(enterprise));
        when(memberships.find(USER_ID, SPACE_ID)).thenReturn(Optional.empty());
        when(time.now()).thenReturn(NOW);
        service = new KnowledgeSpaceAccessService(spaces, memberships, users,
                KnowledgeSpaceTelemetryPort.noOp(), events, time);

        service.requireReadable(actor, SPACE_ID);

        verify(events).recordResource(SecurityEventType.SPACE_ACCESS_DECISION, USER_ID,
                "KNOWLEDGE_SPACE", SPACE_ID.toString(), USER_ID.toString(),
                "SUCCEEDED", "ALLOWED", NOW);
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

    /** 每次新请求都必须重新读取成员、账号和空间状态，不得使用 Token 角色快照。 */
    @Test
    void shouldApplyMembershipAccountAndSpaceChangesImmediately() {
        AtomicReference<UserAccount> account = new AtomicReference<>(activeUser());
        AtomicReference<SpaceMembership> membership = new AtomicReference<>(membership());
        AtomicReference<KnowledgeSpace> space = new AtomicReference<>(space(
                KnowledgeSpaceVisibility.RESTRICTED, KnowledgeSpaceStatus.ACTIVE));
        when(users.findByUserId(USER_ID)).thenAnswer(ignored -> Optional.of(account.get()));
        when(spaces.findBySpaceId(SPACE_ID)).thenAnswer(ignored -> Optional.of(space.get()));
        when(memberships.find(USER_ID, SPACE_ID)).thenAnswer(ignored -> Optional.of(membership.get()));

        service.requireRole(actor, SPACE_ID, SpaceRole.MANAGER);
        membership.set(membership().revoke("admin", NOW.plusSeconds(1)));
        assertEquals(ErrorCode.KNOWLEDGE_SPACE_NOT_FOUND,
                assertThrows(ApplicationException.class,
                        () -> service.requireRole(actor, SPACE_ID, SpaceRole.READER)).errorCode());

        membership.set(membership());
        account.set(activeUser().changeStatus(UserStatus.DISABLED, "admin", NOW.plusSeconds(2)));
        assertEquals(ErrorCode.AUTH_FORBIDDEN,
                assertThrows(ApplicationException.class,
                        () -> service.requireReadable(actor, SPACE_ID)).errorCode());

        account.set(activeUser());
        space.set(space(KnowledgeSpaceVisibility.RESTRICTED, KnowledgeSpaceStatus.DISABLED));
        assertEquals(ErrorCode.KNOWLEDGE_SPACE_DISABLED,
                assertThrows(ApplicationException.class,
                        () -> service.requireActiveReadable(actor, SPACE_ID)).errorCode());
    }

    /** 覆盖平台角色、空间可见性、成员角色和空间状态的完整读取与治理矩阵。 */
    @Test
    void shouldEnforceCompleteRoleVisibilityAndStatusMatrix() {
        for (UserRole platformRole : UserRole.values()) {
            AuthenticatedUser currentActor = new AuthenticatedUser(
                    USER_ID, "member", platformRole);
            for (KnowledgeSpaceVisibility visibility : KnowledgeSpaceVisibility.values()) {
                for (KnowledgeSpaceStatus status : KnowledgeSpaceStatus.values()) {
                    for (SpaceRole role : SpaceRole.values()) {
                        when(spaces.findBySpaceId(SPACE_ID)).thenReturn(Optional.of(
                                space(visibility, status)));
                        when(memberships.find(USER_ID, SPACE_ID)).thenReturn(Optional.of(
                                new SpaceMembership(18L, USER_ID, SPACE_ID, role,
                                        SpaceMembershipStatus.ACTIVE, 0, "admin", NOW,
                                        "admin", NOW, null, null)));
                        if (status == KnowledgeSpaceStatus.DISABLED) {
                            assertEquals(ErrorCode.KNOWLEDGE_SPACE_DISABLED,
                                    assertThrows(ApplicationException.class,
                                            () -> service.requireRole(currentActor, SPACE_ID,
                                                    SpaceRole.READER)).errorCode());
                        } else {
                            service.requireRole(currentActor, SPACE_ID, SpaceRole.READER);
                            boolean managerAllowed = platformRole == UserRole.ADMIN
                                    || role == SpaceRole.MANAGER;
                            if (managerAllowed) {
                                service.requireRole(currentActor, SPACE_ID, SpaceRole.MANAGER);
                            } else {
                                assertEquals(ErrorCode.KNOWLEDGE_SPACE_ROLE_REQUIRED,
                                        assertThrows(ApplicationException.class,
                                                () -> service.requireRole(currentActor, SPACE_ID,
                                                        SpaceRole.MANAGER)).errorCode());
                            }
                        }
                    }
                }
            }
        }
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
