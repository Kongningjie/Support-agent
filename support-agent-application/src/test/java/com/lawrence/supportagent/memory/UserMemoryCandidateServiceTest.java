package com.lawrence.supportagent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.memory.port.MemoryCandidateTelemetryPort;
import com.lawrence.supportagent.memory.port.UserMemoryCandidatePort;
import com.lawrence.supportagent.memory.port.UserMemoryRepository;
import com.lawrence.supportagent.model.ModelInvocationException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 验证候选生成的无等待并发、故障隔离、许可释放和结果分类。 */
@ExtendWith(MockitoExtension.class)
class UserMemoryCandidateServiceTest {
    @Mock private UserMemoryRepository repository;
    @Mock private UserMemoryCandidatePort model;
    private final UUID userId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-20T10:00:00Z");

    /** 默认关闭时不得调用候选模型。 */
    @Test void shouldSkipModelWhenDisabled() {
        CapturingTelemetry telemetry = new CapturingTelemetry();
        when(repository.findSettings(userId)).thenReturn(Optional.empty());

        service(Runnable::run, telemetry, 4).afterSuccessfulTurn(
                userId, UUID.randomUUID(), UUID.randomUUID(), "使用中文");

        verify(model, never()).propose(any());
        assertEquals(1, telemetry.requests);
    }

    /** 开启后保存安全候选，并把写入截止时间固定为当前时刻减去 30 天。 */
    @Test void shouldStoreSafeCandidateWithRetentionCutoff() {
        CapturingTelemetry telemetry = new CapturingTelemetry();
        enable(userId);
        when(model.propose("使用 PowerShell 7")).thenReturn(List.of(
                new UserMemoryCandidate(MemoryType.CONSTRAINT, "统一使用 PowerShell 7")));
        when(repository.insertCandidate(any(), eq(now.minus(Duration.ofDays(30)))))
                .thenReturn(CandidateInsertResult.inserted(memory(userId)));

        service(Runnable::run, telemetry, 4).afterSuccessfulTurn(
                userId, UUID.randomUUID(), UUID.randomUUID(), "使用 PowerShell 7");

        verify(repository).insertCandidate(any(UserMemory.class),
                eq(now.minus(Duration.ofDays(30))));
        assertEquals(List.of(MemoryCandidateTelemetryPort.CandidateOutcome.SUCCESS),
                telemetry.outcomes);
        assertEquals(List.of(CandidateInsertOutcome.INSERTED), telemetry.insertions);
        assertEquals(0, telemetry.inFlight);
    }

    /** 同一用户已有请求时立即拒绝第二个请求，释放后允许再次执行。 */
    @Test void shouldRejectSecondRequestForSameUserAndReleasePermit() throws Exception {
        CapturingTelemetry telemetry = new CapturingTelemetry();
        enable(userId);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(model.propose(any())).thenAnswer(ignored -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return List.of();
        });
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            UserMemoryCandidateService service = service(executor, telemetry, 4);
            service.afterSuccessfulTurn(userId, UUID.randomUUID(), UUID.randomUUID(), "first");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.afterSuccessfulTurn(userId, UUID.randomUUID(), UUID.randomUUID(), "second");
            assertEquals(List.of(MemoryCandidateTelemetryPort.CandidateRejectionReason.USER_LIMIT),
                    telemetry.rejections);
            release.countDown();
        }
        assertEquals(0, telemetry.inFlight);
    }

    /** 四个不同用户可并行，第五个不同用户在全局上限处立即拒绝。 */
    @Test void shouldAllowFourUsersAndRejectFifthGlobally() throws Exception {
        CapturingTelemetry telemetry = new CapturingTelemetry();
        List<UUID> users = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        users.forEach(this::enable);
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        when(model.propose(any())).thenAnswer(ignored -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return List.of();
        });
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            UserMemoryCandidateService service = service(executor, telemetry, 4);
            users.subList(0, 4).forEach(value -> service.afterSuccessfulTurn(
                    value, UUID.randomUUID(), UUID.randomUUID(), "并行"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.afterSuccessfulTurn(users.get(4), UUID.randomUUID(), UUID.randomUUID(), "第五个");
            assertEquals(List.of(MemoryCandidateTelemetryPort.CandidateRejectionReason.GLOBAL_LIMIT),
                    telemetry.rejections);
            assertEquals(4, telemetry.inFlight);
            release.countDown();
        }
        assertEquals(0, telemetry.inFlight);
    }

    /** 执行器拒绝必须释放已经取得的全局与用户许可。 */
    @Test void shouldReleasePermitsWhenExecutorRejects() {
        CapturingTelemetry telemetry = new CapturingTelemetry();
        enable(userId);
        when(model.propose(any())).thenReturn(List.of());
        AtomicInteger submissions = new AtomicInteger();
        Executor rejectingOnce = command -> {
            if (submissions.getAndIncrement() == 0) {
                throw new IllegalStateException("rejected");
            }
            command.run();
        };
        UserMemoryCandidateService service = service(rejectingOnce, telemetry, 1);

        service.afterSuccessfulTurn(
                userId, UUID.randomUUID(), UUID.randomUUID(), "不会执行");
        service.afterSuccessfulTurn(
                userId, UUID.randomUUID(), UUID.randomUUID(), "释放后执行");

        assertEquals(List.of(MemoryCandidateTelemetryPort.CandidateFailureReason.EXECUTOR),
                telemetry.failures);
        verify(model).propose("释放后执行");
        assertEquals(0, telemetry.inFlight);
    }

    /** 遥测实现异常不得影响模型执行，也不得泄漏同用户或全局许可。 */
    @Test void shouldIsolateTelemetryFailureAndReleasePermits() {
        enable(userId);
        when(model.propose(any())).thenReturn(List.of());
        UserMemoryCandidateService service = new UserMemoryCandidateService(
                repository, model, new UserMemoryContentPolicy(), UUID::randomUUID,
                () -> now, Runnable::run, new ThrowingTelemetry(),
                new UserMemoryCandidateSettings(Duration.ofDays(30), 1, 1, 500));

        service.afterSuccessfulTurn(userId, UUID.randomUUID(), UUID.randomUUID(), "first");
        service.afterSuccessfulTurn(userId, UUID.randomUUID(), UUID.randomUUID(), "second");

        verify(model, org.mockito.Mockito.times(2)).propose(any());
    }

    /** 模型、Schema、敏感内容和数据库异常均分类记录并释放许可。 */
    @Test void shouldClassifyFailuresAndReleasePermits() {
        CapturingTelemetry telemetry = new CapturingTelemetry();
        enable(userId);
        when(model.propose(any()))
                .thenThrow(new ModelInvocationException("MODEL_TIMEOUT", "超时", false, null))
                .thenThrow(new ModelInvocationException(
                        "MEMORY_CANDIDATE_MODEL_UNAVAILABLE", "模型不可用", false, null))
                .thenThrow(new ModelInvocationException(
                        "MEMORY_CANDIDATE_SCHEMA_INVALID", "结构错误", false, null))
                .thenReturn(List.of(new UserMemoryCandidate(
                        MemoryType.ENVIRONMENT, "token=abcdefghijklmnop")))
                .thenReturn(List.of(new UserMemoryCandidate(
                        MemoryType.PREFERENCE, "使用简体中文")));
        when(repository.insertCandidate(any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));
        UserMemoryCandidateService service = service(Runnable::run, telemetry, 1);

        for (int index = 0; index < 5; index++) {
            service.afterSuccessfulTurn(userId, UUID.randomUUID(), UUID.randomUUID(), "test");
        }

        assertEquals(List.of(MemoryCandidateTelemetryPort.CandidateFailureReason.MODEL,
                MemoryCandidateTelemetryPort.CandidateFailureReason.MODEL,
                MemoryCandidateTelemetryPort.CandidateFailureReason.SCHEMA,
                MemoryCandidateTelemetryPort.CandidateFailureReason.SENSITIVE_CONTENT,
                MemoryCandidateTelemetryPort.CandidateFailureReason.DATABASE), telemetry.failures);
        assertEquals(0, telemetry.inFlight);
    }

    /** 为指定用户模拟已经启用的长期记忆设置。 */
    private void enable(UUID value) {
        when(repository.findSettings(value)).thenReturn(Optional.of(
                new UserMemorySettings(1L, value, true, 1, now, now)));
    }

    /** 创建测试所需的待确认记忆。 */
    private UserMemory memory(UUID owner) {
        return UserMemory.propose(UUID.randomUUID(), owner, MemoryType.PREFERENCE,
                "使用简体中文", "a".repeat(64), UUID.randomUUID(), UUID.randomUUID(), now);
    }

    /** 创建使用 30 天保留期和单用户并发 1 的待测服务。 */
    private UserMemoryCandidateService service(Executor executor,
                                               CapturingTelemetry telemetry,
                                               int globalConcurrency) {
        return new UserMemoryCandidateService(repository, model, new UserMemoryContentPolicy(),
                UUID::randomUUID, () -> now, executor, telemetry,
                new UserMemoryCandidateSettings(Duration.ofDays(30), globalConcurrency, 1, 500));
    }

    /** 只保存低基数分类和当前执行数的测试遥测实现。 */
    private static final class CapturingTelemetry implements MemoryCandidateTelemetryPort {
        private int requests;
        private int inFlight;
        private final List<CandidateOutcome> outcomes = Collections.synchronizedList(new ArrayList<>());
        private final List<CandidateRejectionReason> rejections =
                Collections.synchronizedList(new ArrayList<>());
        private final List<CandidateFailureReason> failures =
                Collections.synchronizedList(new ArrayList<>());
        private final List<CandidateInsertOutcome> insertions =
                Collections.synchronizedList(new ArrayList<>());

        /** {@inheritDoc} */
        @Override public synchronized void recordRequest() { requests++; }
        /** {@inheritDoc} */
        @Override public void recordOutcome(CandidateOutcome outcome) { outcomes.add(outcome); }
        /** {@inheritDoc} */
        @Override public void recordRejection(CandidateRejectionReason reason) { rejections.add(reason); }
        /** {@inheritDoc} */
        @Override public void recordFailure(CandidateFailureReason reason) { failures.add(reason); }
        /** {@inheritDoc} */
        @Override public void recordInsertion(CandidateInsertOutcome outcome) { insertions.add(outcome); }
        /** {@inheritDoc} */
        @Override public synchronized void executionStarted() { inFlight++; }
        /** {@inheritDoc} */
        @Override public synchronized void executionFinished() { inFlight--; }
        /** {@inheritDoc} */
        @Override public void recordDuration(long durationMs) { }
        /** {@inheritDoc} */
        @Override public void recordCleanup(int deletedCount, long durationMs,
                                            boolean succeeded) { }
    }

    /** 在每个指标入口抛错，用于验证遥测故障隔离。 */
    private static final class ThrowingTelemetry implements MemoryCandidateTelemetryPort {
        /** 始终抛出测试异常。 */
        private void fail() { throw new IllegalStateException("telemetry unavailable"); }
        /** {@inheritDoc} */ @Override public void recordRequest() { fail(); }
        /** {@inheritDoc} */ @Override public void recordOutcome(CandidateOutcome outcome) { fail(); }
        /** {@inheritDoc} */ @Override public void recordRejection(CandidateRejectionReason reason) { fail(); }
        /** {@inheritDoc} */ @Override public void recordFailure(CandidateFailureReason reason) { fail(); }
        /** {@inheritDoc} */ @Override public void recordInsertion(CandidateInsertOutcome outcome) { fail(); }
        /** {@inheritDoc} */ @Override public void executionStarted() { fail(); }
        /** {@inheritDoc} */ @Override public void executionFinished() { fail(); }
        /** {@inheritDoc} */ @Override public void recordDuration(long durationMs) { fail(); }
        /** {@inheritDoc} */
        @Override public void recordCleanup(int deletedCount, long durationMs,
                                            boolean succeeded) { fail(); }
    }
}
