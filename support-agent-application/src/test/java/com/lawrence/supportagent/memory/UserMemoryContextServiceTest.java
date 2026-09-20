package com.lawrence.supportagent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.chat.ConservativeTokenEstimator;
import com.lawrence.supportagent.memory.port.UserMemoryRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 验证长期记忆仅在开关开启且预算允许时注入。 */
@ExtendWith(MockitoExtension.class)
class UserMemoryContextServiceTest {
    @Mock private UserMemoryRepository repository;
    private final UUID userId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-20T10:00:00Z");

    /** 默认关闭时即使数据库存在有效记忆也不得注入。 */
    @Test void shouldNotInjectWhenDisabled() {
        when(repository.findSettings(userId)).thenReturn(Optional.empty());
        UserMemoryContextService service = service(1000);
        assertEquals(List.of(), service.contextFor(userId));
    }

    /** 开启后按仓库冻结顺序选择且不超过独立预算。 */
    @Test void shouldSelectWithinIndependentBudget() {
        when(repository.findSettings(userId)).thenReturn(Optional.of(
                new UserMemorySettings(1L, userId, true, 1, now, now)));
        when(repository.findActive(userId, now, 100)).thenReturn(List.of(
                memory("统一使用 PowerShell 7", true), memory("请使用简体中文", false)));
        UserMemoryContextService service = service(20);
        List<String> context = service.contextFor(userId);
        assertEquals(1, context.size());
        assertEquals("[长期记忆|CONSTRAINT] 统一使用 PowerShell 7", context.getFirst());
    }

    /** 创建使用固定时钟的待测服务。 */
    private UserMemoryContextService service(int budget) {
        return new UserMemoryContextService(repository, new ConservativeTokenEstimator(),
                () -> now, budget);
    }

    /** 创建一条有效约束记忆。 */
    private UserMemory memory(String content, boolean pinned) {
        return new UserMemory(1L, UUID.randomUUID(), userId, MemoryType.CONSTRAINT,
                content, "a".repeat(64), MemoryStatus.ACTIVE, pinned,
                UUID.randomUUID(), UUID.randomUUID(), null, 1, "MODEL_CANDIDATE", now,
                userId.toString(), now, userId.toString(), now, null, null);
    }
}
