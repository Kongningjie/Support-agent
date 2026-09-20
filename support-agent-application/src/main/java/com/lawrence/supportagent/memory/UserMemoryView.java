package com.lawrence.supportagent.memory;

import java.time.Instant;
import java.util.UUID;

/** 可安全返回给记忆所有者的长期记忆视图。 */
public record UserMemoryView(UUID memoryId, MemoryType memoryType, String content,
                             MemoryStatus status, boolean pinned, UUID sourceConversationId,
                             UUID sourceTurnId, Instant expiresAt, long version,
                             Instant createdAt, Instant updatedAt) {
    /** 从领域聚合创建不含内部主键和内容哈希的公开视图。 */
    public static UserMemoryView from(UserMemory memory) {
        return new UserMemoryView(memory.memoryId(), memory.memoryType(), memory.content(),
                memory.status(), memory.pinned(), memory.sourceConversationId(),
                memory.sourceTurnId(), memory.expiresAt(), memory.version(),
                memory.createdAt(), memory.updatedAt());
    }
}
