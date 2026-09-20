package com.lawrence.supportagent.memory;

import java.time.Duration;
import java.util.Objects;

/** 候选保留期、单实例全局并发、单用户并发和清理批量的不可变配置。 */
public record UserMemoryCandidateSettings(Duration retention, int globalConcurrency,
                                          int perUserConcurrency, int cleanupBatchSize) {
    /** 校验冻结配置，防止无界并发、无效保留期或过大事务批次。 */
    public UserMemoryCandidateSettings {
        Objects.requireNonNull(retention, "候选保留期不能为空");
        if (retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("候选保留期必须大于 0");
        }
        if (globalConcurrency < 1 || globalConcurrency > 100) {
            throw new IllegalArgumentException("候选全局并发必须在 1 到 100 之间");
        }
        if (perUserConcurrency != 1) {
            throw new IllegalArgumentException("首版候选单用户并发固定为 1");
        }
        if (cleanupBatchSize < 1 || cleanupBatchSize > 10_000) {
            throw new IllegalArgumentException("候选清理批量必须在 1 到 10000 之间");
        }
    }
}
