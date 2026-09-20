package com.lawrence.supportagent.memory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import com.lawrence.supportagent.memory.port.UserMemoryCandidatePort;
import com.lawrence.supportagent.memory.port.UserMemoryRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 验证聊天后候选生成只在用户启用时执行并经过敏感信息门禁。 */
@ExtendWith(MockitoExtension.class)
class UserMemoryCandidateServiceTest {
    @Mock private UserMemoryRepository repository;
    @Mock private UserMemoryCandidatePort model;
    private final UUID userId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-20T10:00:00Z");

    /** 默认关闭时不得调用候选模型。 */
    @Test void shouldSkipModelWhenDisabled() {
        when(repository.findSettings(userId)).thenReturn(Optional.empty());
        service().afterSuccessfulTurn(userId, UUID.randomUUID(), UUID.randomUUID(), "使用中文");
        verify(model, never()).propose(any());
    }

    /** 开启后保存安全候选，并在整次响应含敏感内容时不产生部分写入。 */
    @Test void shouldStoreSafeCandidateAndRejectSensitiveCandidate() {
        when(repository.findSettings(userId)).thenReturn(Optional.of(
                new UserMemorySettings(1L, userId, true, 1, now, now)));
        when(repository.countByUser(userId)).thenReturn(0L);
        when(model.propose("使用 PowerShell 7"))
                .thenReturn(List.of(new UserMemoryCandidate(MemoryType.CONSTRAINT, "统一使用 PowerShell 7")));
        service().afterSuccessfulTurn(userId, UUID.randomUUID(), UUID.randomUUID(), "使用 PowerShell 7");
        verify(repository).insertCandidate(any(UserMemory.class));

        when(model.propose("记住 token"))
                .thenReturn(List.of(new UserMemoryCandidate(MemoryType.ENVIRONMENT,
                        "token=abcdefghijklmnop")));
        service().afterSuccessfulTurn(userId, UUID.randomUUID(), UUID.randomUUID(), "记住 token");
        verify(repository, times(1)).insertCandidate(any(UserMemory.class));
    }

    /** 创建同步执行异步任务的待测服务。 */
    private UserMemoryCandidateService service() {
        return new UserMemoryCandidateService(repository, model, new UserMemoryContentPolicy(),
                UUID::randomUUID, () -> now, Runnable::run);
    }
}
