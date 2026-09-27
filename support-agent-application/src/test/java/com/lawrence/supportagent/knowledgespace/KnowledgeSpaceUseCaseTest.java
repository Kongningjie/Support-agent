package com.lawrence.supportagent.knowledgespace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.auth.port.SecurityEventPort;
import com.lawrence.supportagent.auth.port.UserRepository;
import com.lawrence.supportagent.idempotency.IdempotencyCommand;
import com.lawrence.supportagent.idempotency.IdempotentExecutor;
import com.lawrence.supportagent.idempotency.IdempotentResource;
import com.lawrence.supportagent.knowledgespace.port.KnowledgeSpaceRepository;
import com.lawrence.supportagent.knowledgespace.port.SpaceMembershipRepository;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.user.UserAccount;
import com.lawrence.supportagent.user.UserRole;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 验证空间治理中不能只依赖数据库约束表达的业务规则。 */
class KnowledgeSpaceUseCaseTest {
    private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");
    private static final UUID ACTOR_ID = UUID.fromString("20000000-0000-0000-0000-000000000018");
    private static final UUID TARGET_ID = UUID.fromString("20000000-0000-0000-0000-000000000019");
    private static final UUID SPACE_ID = UUID.fromString("30000000-0000-0000-0000-000000000018");
    private final AuthenticatedUser actor = new AuthenticatedUser(ACTOR_ID, "manager", UserRole.USER);
    private KnowledgeSpaceRepository spaces;
    private SpaceMembershipRepository memberships;
    private UserRepository users;
    private KnowledgeSpaceAccessService access;
    private KnowledgeSpaceUseCase useCase;

    /** 创建端口替身并使用同步幂等执行器执行首次动作。 */
    @BeforeEach
    void setUp() {
        spaces = mock(KnowledgeSpaceRepository.class);
        memberships = mock(SpaceMembershipRepository.class);
        users = mock(UserRepository.class);
        access = mock(KnowledgeSpaceAccessService.class);
        useCase = new KnowledgeSpaceUseCase(spaces, memberships, users, access,
                new ImmediateIdempotentExecutor(), UUID::randomUUID, () -> NOW,
                mock(SecurityEventPort.class));
        when(users.findByUserId(TARGET_ID)).thenReturn(Optional.of(UserAccount.create(TARGET_ID,
                "target", "目标用户", "hash", UserRole.USER, "admin", NOW)));
    }

    /** 受限空间最后一个活动 MANAGER 不能被撤销。 */
    @Test
    void shouldProtectLastRestrictedManager() {
        KnowledgeSpace space = space(KnowledgeSpaceVisibility.RESTRICTED);
        SpaceMembership membership = SpaceMembership.create(TARGET_ID, SPACE_ID,
                SpaceRole.MANAGER, ACTOR_ID.toString(), NOW);
        when(access.requireRole(actor, SPACE_ID, SpaceRole.MANAGER)).thenReturn(space);
        when(spaces.findBySpaceIdForUpdate(SPACE_ID)).thenReturn(Optional.of(space));
        when(memberships.find(TARGET_ID, SPACE_ID)).thenReturn(Optional.of(membership));
        when(memberships.countActiveManagers(SPACE_ID)).thenReturn(1L);

        ApplicationException exception = assertThrows(ApplicationException.class,
                () -> useCase.revokeMember(actor, SPACE_ID, TARGET_ID, 0, "revoke-1"));

        assertEquals(ErrorCode.KNOWLEDGE_SPACE_MEMBERSHIP_CONFLICT, exception.errorCode());
        verify(memberships, never()).save(org.mockito.ArgumentMatchers.any());
    }

    /** GLOBAL 的企业读取是隐式权限，不保存冗余 READER 成员。 */
    @Test
    void shouldRejectExplicitGlobalReader() {
        when(access.requireRole(actor, KnowledgeSpace.GLOBAL_SPACE_ID, SpaceRole.MANAGER))
                .thenReturn(space(KnowledgeSpaceVisibility.ENTERPRISE));

        ApplicationException exception = assertThrows(ApplicationException.class,
                () -> useCase.putMember(actor, KnowledgeSpace.GLOBAL_SPACE_ID, TARGET_ID,
                        SpaceRole.READER, null, "global-reader-1"));

        assertEquals(ErrorCode.KNOWLEDGE_SPACE_MEMBERSHIP_CONFLICT, exception.errorCode());
    }

    /** 无权空间必须先返回空间 404，不能通过目标账号查询顺序泄露用户存在性。 */
    @Test
    void shouldAuthorizeSpaceBeforeLookingUpTargetUser() {
        ApplicationException hidden = new ApplicationException(
                ErrorCode.KNOWLEDGE_SPACE_NOT_FOUND, "知识空间不存在");
        when(access.requireRole(actor, SPACE_ID, SpaceRole.MANAGER)).thenThrow(hidden);

        ApplicationException exception = assertThrows(ApplicationException.class,
                () -> useCase.putMember(actor, SPACE_ID, UUID.randomUUID(),
                        SpaceRole.READER, null, "hidden-space-1"));

        assertEquals(ErrorCode.KNOWLEDGE_SPACE_NOT_FOUND, exception.errorCode());
        verify(users, never()).findByUserId(org.mockito.ArgumentMatchers.any());
    }

    /** 创建普通活动空间快照。 */
    private KnowledgeSpace space(KnowledgeSpaceVisibility visibility) {
        UUID id = visibility == KnowledgeSpaceVisibility.ENTERPRISE
                ? KnowledgeSpace.GLOBAL_SPACE_ID : SPACE_ID;
        String code = visibility == KnowledgeSpaceVisibility.ENTERPRISE ? "GLOBAL" : "TEAM_OPS";
        return new KnowledgeSpace(18L, id, code, "测试空间", null, visibility,
                KnowledgeSpaceStatus.ACTIVE, visibility == KnowledgeSpaceVisibility.ENTERPRISE,
                0, "admin", NOW, "admin", NOW);
    }

    /** 测试中直接执行首次幂等动作，不模拟持久化协调。 */
    private static final class ImmediateIdempotentExecutor implements IdempotentExecutor {
        /** {@inheritDoc} */
        @Override
        public <T> T execute(IdempotencyCommand command,
                             Supplier<IdempotentResource<T>> action,
                             LongFunction<T> replayLoader) {
            return action.get().value();
        }
    }
}
