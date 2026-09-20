package com.lawrence.supportagent.memory;

import com.lawrence.supportagent.memory.port.MemoryCandidateTelemetryPort;
import com.lawrence.supportagent.memory.port.UserMemoryRepository;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;

/** 编排单批过期待确认候选清理，并把故障隔离在辅助能力内部。 */
public class UserMemoryCleanupService {
    private final UserMemoryRepository repository;
    private final MemoryCandidateTelemetryPort telemetry;
    private final TimeProvider time;
    private final UserMemoryCandidateSettings settings;

    /** 注入候选仓库、低基数遥测、统一时钟和冻结配置。 */
    public UserMemoryCleanupService(UserMemoryRepository repository,
                                    MemoryCandidateTelemetryPort telemetry,
                                    TimeProvider time,
                                    UserMemoryCandidateSettings settings) {
        this.repository = repository;
        this.telemetry = telemetry == null ? MemoryCandidateTelemetryPort.noOp() : telemetry;
        this.time = time;
        this.settings = settings;
    }

    /** 清理一批达到保留期的 PROPOSED 候选；失败仅记指标且不影响应用就绪状态。 */
    public void cleanupOnce() {
        long startedAt = System.nanoTime();
        int deleted = 0;
        boolean succeeded = false;
        try {
            deleted = repository.cleanupExpiredProposed(
                    time.now().minus(settings.retention()), settings.cleanupBatchSize());
            succeeded = true;
        } catch (RuntimeException ignored) {
            // 清理是可在下一周期恢复的辅助能力，不向日志写入异常正文或业务标识。
        } finally {
            recordCleanupSafely(deleted, elapsedMillis(startedAt), succeeded);
        }
    }

    /** 隔离指标实现故障，避免定时调度因遥测异常中断。 */
    private void recordCleanupSafely(int deleted, long durationMs, boolean succeeded) {
        try {
            telemetry.recordCleanup(deleted, durationMs, succeeded);
        } catch (RuntimeException ignored) {
            // 遥测失败不改变数据库清理结果，也不传播到调度线程。
        }
    }

    /** 把单调时钟差转换为非负毫秒。 */
    private long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }
}
