package com.lawrence.supportagent.memory;

import com.lawrence.supportagent.memory.UserMemoryContentPolicy.NormalizedMemory;
import com.lawrence.supportagent.memory.port.MemoryCandidateTelemetryPort;
import com.lawrence.supportagent.memory.port.MemoryCandidateTelemetryPort.CandidateFailureReason;
import com.lawrence.supportagent.memory.port.MemoryCandidateTelemetryPort.CandidateOutcome;
import com.lawrence.supportagent.memory.port.MemoryCandidateTelemetryPort.CandidateRejectionReason;
import com.lawrence.supportagent.memory.port.UserMemoryCandidatePort;
import com.lawrence.supportagent.memory.port.UserMemoryRepository;
import com.lawrence.supportagent.model.ModelInvocationException;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.sharedkernel.port.UuidGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

/** 在聊天成功后以无等待限流和故障隔离方式生成待用户确认的长期记忆候选。 */
public class UserMemoryCandidateService {
    private final UserMemoryRepository repository;
    private final UserMemoryCandidatePort model;
    private final UserMemoryContentPolicy contentPolicy;
    private final UuidGenerator ids;
    private final TimeProvider time;
    private final Executor executor;
    private final MemoryCandidateTelemetryPort telemetry;
    private final UserMemoryCandidateSettings settings;
    private final Semaphore globalPermits;
    private final ConcurrentHashMap<UUID, Semaphore> userPermits = new ConcurrentHashMap<>();

    /** 注入持久化、模型、安全策略、基础能力、执行器、遥测和冻结运行配置。 */
    public UserMemoryCandidateService(UserMemoryRepository repository,
                                      UserMemoryCandidatePort model,
                                      UserMemoryContentPolicy contentPolicy,
                                      UuidGenerator ids, TimeProvider time, Executor executor,
                                      MemoryCandidateTelemetryPort telemetry,
                                      UserMemoryCandidateSettings settings) {
        this.repository = repository;
        this.model = model;
        this.contentPolicy = contentPolicy;
        this.ids = ids;
        this.time = time;
        this.executor = executor;
        this.telemetry = telemetry == null ? MemoryCandidateTelemetryPort.noOp() : telemetry;
        this.settings = settings;
        this.globalPermits = new Semaphore(settings.globalConcurrency());
    }

    /** 用户开启记忆时调度本轮候选提取；拒绝或失败不得改变聊天结果。 */
    public void afterSuccessfulTurn(UUID userId, UUID conversationId,
                                    UUID clientMessageId, String userMessage) {
        recordTelemetry(telemetry::recordRequest);
        try {
            if (!repository.findSettings(userId).map(UserMemorySettings::enabled).orElse(false)) {
                return;
            }
        } catch (RuntimeException ignored) {
            recordTelemetry(() -> telemetry.recordFailure(CandidateFailureReason.DATABASE));
            return;
        }
        PermitLease lease = tryAcquire(userId);
        if (lease == null) {
            return;
        }
        recordTelemetry(telemetry::executionStarted);
        try {
            executor.execute(() -> generate(userId, conversationId, clientMessageId,
                    userMessage, lease));
        } catch (RuntimeException ignored) {
            finish(lease, lease.startedAt());
            recordTelemetry(() -> telemetry.recordFailure(CandidateFailureReason.EXECUTOR));
        }
    }

    /** 取得单用户许可后再取得全局许可；任一许可不可立即取得时拒绝请求。 */
    private PermitLease tryAcquire(UUID userId) {
        Semaphore userPermit = tryAcquireUser(userId);
        if (userPermit == null) {
            recordTelemetry(() -> telemetry.recordRejection(CandidateRejectionReason.USER_LIMIT));
            return null;
        }
        if (!globalPermits.tryAcquire()) {
            releaseUser(userId, userPermit);
            recordTelemetry(() -> telemetry.recordRejection(CandidateRejectionReason.GLOBAL_LIMIT));
            return null;
        }
        return new PermitLease(userId, userPermit, System.nanoTime());
    }

    /** 在用户映射的原子更新内尝试取得单用户许可，避免清理映射时出现双重许可。 */
    private Semaphore tryAcquireUser(UUID userId) {
        Semaphore[] acquired = new Semaphore[1];
        userPermits.compute(userId, (ignored, existing) -> {
            Semaphore permit = existing == null
                    ? new Semaphore(settings.perUserConcurrency()) : existing;
            if (permit.tryAcquire()) {
                acquired[0] = permit;
            }
            return permit;
        });
        return acquired[0];
    }

    /** 调用独立模型并保存整批通过确定性安全校验的候选。 */
    private void generate(UUID userId, UUID conversationId, UUID clientMessageId,
                          String userMessage, PermitLease lease) {
        try {
            List<UserMemoryCandidate> candidates = model.propose(userMessage);
            if (candidates.isEmpty()) {
                recordTelemetry(() -> telemetry.recordOutcome(CandidateOutcome.EMPTY));
                return;
            }
            List<UserMemory> validated = validateCandidates(
                    userId, conversationId, clientMessageId, candidates);
            recordTelemetry(() -> telemetry.recordOutcome(CandidateOutcome.SUCCESS));
            Instant cutoff = time.now().minus(settings.retention());
            for (UserMemory memory : validated) {
                CandidateInsertResult result = repository.insertCandidate(memory, cutoff);
                recordTelemetry(() -> telemetry.recordInsertion(result.outcome()));
                if (result.outcome() == CandidateInsertOutcome.DISABLED
                        || result.outcome() == CandidateInsertOutcome.LIMIT_REACHED) {
                    break;
                }
            }
        } catch (ModelInvocationException exception) {
            CandidateFailureReason reason = exception.errorCode().contains("SCHEMA")
                    ? CandidateFailureReason.SCHEMA : CandidateFailureReason.MODEL;
            recordTelemetry(() -> telemetry.recordFailure(reason));
        } catch (ApplicationException exception) {
            CandidateFailureReason reason = exception.errorCode() == ErrorCode.MEMORY_SENSITIVE_CONTENT
                    ? CandidateFailureReason.SENSITIVE_CONTENT : CandidateFailureReason.DATABASE;
            recordTelemetry(() -> telemetry.recordFailure(reason));
        } catch (IllegalArgumentException exception) {
            recordTelemetry(() -> telemetry.recordFailure(CandidateFailureReason.SCHEMA));
        } catch (RuntimeException exception) {
            recordTelemetry(() -> telemetry.recordFailure(CandidateFailureReason.DATABASE));
        } finally {
            finish(lease, lease.startedAt());
        }
    }

    /** 在任何数据库写入前校验最多三个候选，防止响应后段失败造成部分落库。 */
    private List<UserMemory> validateCandidates(UUID userId, UUID conversationId,
                                                UUID clientMessageId,
                                                List<UserMemoryCandidate> candidates) {
        List<UserMemory> validated = new ArrayList<>();
        for (UserMemoryCandidate candidate : candidates.stream().limit(3).toList()) {
            NormalizedMemory normalized = contentPolicy.normalize(candidate.content());
            validated.add(UserMemory.propose(ids.generate(), userId,
                    candidate.memoryType(), normalized.content(), normalized.contentHash(),
                    conversationId, clientMessageId, time.now()));
        }
        return List.copyOf(validated);
    }

    /** 释放全部许可并记录执行时长和当前执行数。 */
    private void finish(PermitLease lease, long startedAt) {
        globalPermits.release();
        releaseUser(lease.userId(), lease.userPermit());
        recordTelemetry(telemetry::executionFinished);
        long durationMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        recordTelemetry(() -> telemetry.recordDuration(durationMs));
    }

    /** 在用户映射的原子更新内释放许可，并删除已经空闲的用户项。 */
    private void releaseUser(UUID userId, Semaphore expected) {
        userPermits.computeIfPresent(userId, (ignored, current) -> {
            if (current != expected) {
                return current;
            }
            current.release();
            return current.availablePermits() == settings.perUserConcurrency() ? null : current;
        });
    }

    /** 隔离指标实现故障，确保遥测不可改变候选许可与聊天完成语义。 */
    private void recordTelemetry(Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException ignored) {
            // 遥测是辅助能力，失败不得改变候选执行或聊天成功结果。
        }
    }

    /** 持有单用户许可、全局许可和取得许可的单调时钟起点。 */
    private record PermitLease(UUID userId, Semaphore userPermit, long startedAt) { }
}
