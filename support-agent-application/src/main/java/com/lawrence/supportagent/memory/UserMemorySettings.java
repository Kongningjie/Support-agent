package com.lawrence.supportagent.memory;

import java.time.Instant;
import java.util.UUID;

/** 当前用户长期记忆开关及其乐观锁版本。 */
public record UserMemorySettings(Long id, UUID userId, boolean enabled, long version,
                                 Instant createdAt, Instant updatedAt) {
    /** 校验设置标识、版本和审计时间。 */
    public UserMemorySettings {
        if (userId == null || version < 0 || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("记忆设置用户、版本和时间不能为空");
        }
    }
}
