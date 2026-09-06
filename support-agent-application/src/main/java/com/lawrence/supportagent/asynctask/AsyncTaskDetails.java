package com.lawrence.supportagent.asynctask;

import java.time.Instant;

/**
 * 异步任务运维视图，隐藏 Worker 标识和锁租约。
 *
 * @param taskId 任务内部 ID 的十进制字符串
 * @param taskType 任务业务类型
 * @param aggregateType 关联聚合类型
 * @param aggregateId 关联聚合 ID 的十进制字符串
 * @param aggregateVersion 创建任务时的聚合版本
 * @param status 当前执行状态
 * @param attemptCount 已开始执行次数
 * @param maxAttempts 最大执行次数
 * @param nextRunAt 下一次允许执行 UTC 时间
 * @param lastErrorCode 最近失败稳定错误码，可为空
 * @param lastErrorMessage 最近失败脱敏摘要，可为空
 * @param retryOfTaskId 人工重试来源任务 ID，可为空
 * @param manualRetryReason 人工重试原因，可为空
 * @param createdAt 创建 UTC 时间
 * @param startedAt 首次开始 UTC 时间，可为空
 * @param finishedAt 最终结束 UTC 时间，可为空
 * @param updatedAt 最近更新 UTC 时间
 */
public record AsyncTaskDetails(String taskId, AsyncTaskType taskType,
                               AggregateType aggregateType, String aggregateId,
                               long aggregateVersion, AsyncTaskStatus status,
                               int attemptCount, int maxAttempts, Instant nextRunAt,
                               String lastErrorCode, String lastErrorMessage,
                               String retryOfTaskId, String manualRetryReason,
                               Instant createdAt, Instant startedAt,
                               Instant finishedAt, Instant updatedAt) {
    /** 从任务聚合创建不包含执行锁的运维视图。 */
    public static AsyncTaskDetails from(AsyncTask task) {
        return new AsyncTaskDetails(Long.toString(task.id()), task.taskType(),
                task.aggregateType(), Long.toString(task.aggregateId()),
                task.aggregateVersion(), task.status(), task.attemptCount(),
                task.maxAttempts(), task.nextRunAt(), task.lastErrorCode(),
                task.lastErrorMessage(), task.retryOfTaskId() == null
                        ? null : Long.toString(task.retryOfTaskId()),
                task.manualRetryReason(), task.createdAt(), task.startedAt(),
                task.finishedAt(), task.updatedAt());
    }
}
