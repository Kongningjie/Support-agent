package com.lawrence.supportagent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 验证长期记忆候选、确认、更正和撤销的领域状态规则。 */
class UserMemoryTest {
    private static final Instant NOW = Instant.parse("2026-09-20T10:00:00Z");

    /** 候选只能确认一次，并在确认、更正和撤销时递增版本。 */
    @Test void shouldEnforceLifecycleAndVersions() {
        UserMemory proposed = candidate();
        UserMemory active = proposed.confirm(proposed.userId().toString(), NOW.plusSeconds(1));
        UserMemory revised = active.revise("统一使用 PowerShell 7", "b".repeat(64), null,
                true, active.userId().toString(), NOW.plusSeconds(2));
        UserMemory revoked = revised.revoke(revised.userId().toString(), NOW.plusSeconds(3));

        assertEquals(MemoryStatus.PROPOSED, proposed.status());
        assertEquals(MemoryStatus.ACTIVE, active.status());
        assertEquals(1, active.version());
        assertEquals(2, revised.version());
        assertEquals(true, revised.pinned());
        assertEquals(MemoryStatus.REVOKED, revoked.status());
        assertEquals(3, revoked.version());
        assertThrows(IllegalStateException.class, () -> revoked.revoke("operator", NOW));
        assertThrows(IllegalStateException.class, () -> revoked.revise("x", "c".repeat(64),
                null, false, "operator", NOW));
    }

    /** 创建一条合法环境候选。 */
    private UserMemory candidate() {
        return UserMemory.propose(UUID.randomUUID(), UUID.randomUUID(), MemoryType.ENVIRONMENT,
                "开发环境使用 Windows 11", "a".repeat(64), UUID.randomUUID(), UUID.randomUUID(), NOW);
    }
}
