package com.lawrence.supportagent.knowledgespace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 验证知识空间系统不变量、状态迁移和角色层级。 */
class KnowledgeSpaceTest {
    private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");

    /** 普通空间默认受限、活动，并在修改时递增版本。 */
    @Test
    void shouldCreateAndReviseRestrictedSpace() {
        KnowledgeSpace created = KnowledgeSpace.create(UUID.randomUUID(), "team_ops",
                "运维知识", "仅运维团队可读", "admin", NOW);

        KnowledgeSpace revised = created.revise("生产运维知识", null,
                KnowledgeSpaceVisibility.ENTERPRISE, "admin", NOW.plusSeconds(1));

        assertEquals("TEAM_OPS", created.code());
        assertEquals(KnowledgeSpaceVisibility.RESTRICTED, created.visibility());
        assertEquals(KnowledgeSpaceStatus.ACTIVE, created.status());
        assertEquals(1, revised.version());
        assertEquals(KnowledgeSpaceVisibility.ENTERPRISE, revised.visibility());
    }

    /** 系统空间必须严格保持固定 GLOBAL 标识、可见性和活动状态。 */
    @Test
    void shouldRejectInvalidSystemSpace() {
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeSpace(null,
                UUID.randomUUID(), "GLOBAL", "公共空间", null,
                KnowledgeSpaceVisibility.ENTERPRISE, KnowledgeSpaceStatus.ACTIVE,
                true, 0, "system", NOW, "system", NOW));
    }

    /** MANAGER 包含 EDITOR 和 READER，EDITOR 只额外包含 READER。 */
    @Test
    void shouldApplyRoleHierarchy() {
        assertTrue(SpaceRole.MANAGER.includes(SpaceRole.EDITOR));
        assertTrue(SpaceRole.MANAGER.includes(SpaceRole.READER));
        assertTrue(SpaceRole.EDITOR.includes(SpaceRole.READER));
    }
}
