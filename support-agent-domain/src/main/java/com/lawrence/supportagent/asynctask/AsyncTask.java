package com.lawrence.supportagent.asynctask;

import com.lawrence.supportagent.sharedkernel.DomainAssertions;
import java.time.Instant;

/** 持久化异步任务聚合，维护租约、重试和终态规则。 */
public record AsyncTask(Long id, AsyncTaskType taskType, AggregateType aggregateType,
                        long aggregateId, long aggregateVersion, String idempotencyKey,
                        AsyncTaskStatus status, int attemptCount, int maxAttempts,
                        Instant nextRunAt, String lockedBy, Instant lockedUntil,
                        String lastErrorCode, String lastErrorMessage, Long retryOfTaskId,
                        String manualRetryReason, String createdBy, Instant createdAt,
                        Instant startedAt, Instant finishedAt, Instant updatedAt) {
    /** 校验任务类型、次数、关联聚合及时间字段。 */
    public AsyncTask {
        if (taskType == null || aggregateType == null || status == null || aggregateId <= 0
                || aggregateVersion < 0 || attemptCount < 0 || maxAttempts <= 0
                || attemptCount > maxAttempts || nextRunAt == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("异步任务字段不满足约束");
        }
        idempotencyKey = DomainAssertions.requiredText(idempotencyKey, "任务幂等键");
        createdBy = DomainAssertions.requiredText(createdBy, "创建人");
    }

    /** 取得执行租约并开始一次尝试。 */
    public AsyncTask start(String worker, Instant now, Instant leaseUntil) {
        DomainAssertions.state(status == AsyncTaskStatus.PENDING
                || status == AsyncTaskStatus.RETRY_WAIT, "当前任务不可执行");
        DomainAssertions.state(attemptCount < maxAttempts, "任务已达到最大尝试次数");
        worker = DomainAssertions.requiredText(worker, "Worker 标识");
        if (now == null || leaseUntil == null || !leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("执行时间和租约截止时间不合法");
        }
        return copy(AsyncTaskStatus.RUNNING, attemptCount + 1, now, now, worker, leaseUntil,
                null, null, startedAt == null ? now : startedAt, null);
    }

    /** 标记任务成功完成并释放租约。 */
    public AsyncTask succeed(Instant now) {
        requireRunning();
        return copy(AsyncTaskStatus.SUCCEEDED, attemptCount, now, now, null, null,
                null, null, startedAt, now);
    }

    /** 记录失败；有剩余次数时等待重试，否则进入死亡状态。 */
    public AsyncTask fail(String errorCode, String message, Instant nextAttemptAt, Instant now) {
        requireRunning();
        if (now == null) {
            throw new IllegalArgumentException("失败时间不能为空");
        }
        AsyncTaskStatus target = attemptCount >= maxAttempts ? AsyncTaskStatus.DEAD : AsyncTaskStatus.RETRY_WAIT;
        if (target == AsyncTaskStatus.RETRY_WAIT
                && (nextAttemptAt == null || !nextAttemptAt.isAfter(now))) {
            throw new IllegalArgumentException("下次重试时间必须晚于失败时间");
        }
        return copy(target, attemptCount, target == AsyncTaskStatus.DEAD ? now : nextAttemptAt, now,
                null, null, DomainAssertions.requiredText(errorCode, "错误码"),
                DomainAssertions.requiredText(message, "错误摘要"), startedAt,
                target == AsyncTaskStatus.DEAD ? now : null);
    }

    /** 因关联业务对象失效取消尚未完成的任务。 */
    public AsyncTask cancel(Instant now) {
        DomainAssertions.state(status != AsyncTaskStatus.SUCCEEDED && status != AsyncTaskStatus.DEAD
                && status != AsyncTaskStatus.CANCELLED, "终态任务不能取消");
        return copy(AsyncTaskStatus.CANCELLED, attemptCount, now, now, null, null,
                lastErrorCode, lastErrorMessage, startedAt, now);
    }

    /** 校验任务正在执行。 */
    private void requireRunning() {
        DomainAssertions.state(status == AsyncTaskStatus.RUNNING, "只有运行中任务可以完成或失败");
    }

    /** 复制任务并替换执行状态相关字段。 */
    private AsyncTask copy(AsyncTaskStatus newStatus, int attempts, Instant schedule, Instant modifiedAt,
                           String worker, Instant lease, String errorCode, String errorMessage,
                           Instant started, Instant finished) {
        return new AsyncTask(id, taskType, aggregateType, aggregateId, aggregateVersion,
                idempotencyKey, newStatus, attempts, maxAttempts, schedule, worker, lease,
                errorCode, errorMessage, retryOfTaskId, manualRetryReason, createdBy,
                createdAt, started, finished, modifiedAt);
    }
}
