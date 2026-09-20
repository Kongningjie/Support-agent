package com.lawrence.supportagent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.memory.port.MemoryCandidateTelemetryPort;
import com.lawrence.supportagent.memory.port.UserMemoryRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 验证候选清理使用创建时间保留边界、固定批量并隔离数据库失败。 */
@ExtendWith(MockitoExtension.class)
class UserMemoryCleanupServiceTest {
    @Mock private UserMemoryRepository repository;
    private final Instant now = Instant.parse("2026-09-20T10:00:00Z");

    /** 成功清理使用包含 30 天边界的截止时刻和 500 条批量。 */
    @Test void shouldCleanupOneBatchAtRetentionBoundary() {
        CapturingTelemetry telemetry = new CapturingTelemetry();
        when(repository.cleanupExpiredProposed(now.minus(Duration.ofDays(30)), 500))
                .thenReturn(17);

        service(telemetry).cleanupOnce();

        verify(repository).cleanupExpiredProposed(now.minus(Duration.ofDays(30)), 500);
        assertEquals(17, telemetry.deleted);
        assertEquals(true, telemetry.succeeded);
    }

    /** 数据库失败只记录失败指标，不向调度线程传播异常。 */
    @Test void shouldIsolateCleanupFailure() {
        CapturingTelemetry telemetry = new CapturingTelemetry();
        when(repository.cleanupExpiredProposed(now.minus(Duration.ofDays(30)), 500))
                .thenThrow(new IllegalStateException("database unavailable"));

        service(telemetry).cleanupOnce();

        assertEquals(0, telemetry.deleted);
        assertEquals(false, telemetry.succeeded);
    }

    /** 遥测异常不得从定时清理应用服务传播。 */
    @Test void shouldIsolateCleanupTelemetryFailure() {
        when(repository.cleanupExpiredProposed(now.minus(Duration.ofDays(30)), 500))
                .thenReturn(1);
        MemoryCandidateTelemetryPort throwing = new CapturingTelemetry() {
            /** {@inheritDoc} */
            @Override public void recordCleanup(int deletedCount, long durationMs,
                                                boolean success) {
                throw new IllegalStateException("telemetry unavailable");
            }
        };

        new UserMemoryCleanupService(repository, throwing, () -> now,
                new UserMemoryCandidateSettings(Duration.ofDays(30), 4, 1, 500)).cleanupOnce();

        verify(repository).cleanupExpiredProposed(now.minus(Duration.ofDays(30)), 500);
    }

    /** 创建待测清理应用服务。 */
    private UserMemoryCleanupService service(CapturingTelemetry telemetry) {
        return new UserMemoryCleanupService(repository, telemetry, () -> now,
                new UserMemoryCandidateSettings(Duration.ofDays(30), 4, 1, 500));
    }

    /** 捕获清理聚合结果的无副作用测试遥测。 */
    private static class CapturingTelemetry implements MemoryCandidateTelemetryPort {
        private int deleted;
        private boolean succeeded;
        /** {@inheritDoc} */ @Override public void recordRequest() { }
        /** {@inheritDoc} */ @Override public void recordOutcome(CandidateOutcome outcome) { }
        /** {@inheritDoc} */ @Override public void recordRejection(CandidateRejectionReason reason) { }
        /** {@inheritDoc} */ @Override public void recordFailure(CandidateFailureReason reason) { }
        /** {@inheritDoc} */ @Override public void recordInsertion(CandidateInsertOutcome outcome) { }
        /** {@inheritDoc} */ @Override public void executionStarted() { }
        /** {@inheritDoc} */ @Override public void executionFinished() { }
        /** {@inheritDoc} */ @Override public void recordDuration(long durationMs) { }
        /** {@inheritDoc} */
        @Override public void recordCleanup(int deletedCount, long durationMs,
                                            boolean success) {
            deleted = deletedCount;
            succeeded = success;
        }
    }
}
