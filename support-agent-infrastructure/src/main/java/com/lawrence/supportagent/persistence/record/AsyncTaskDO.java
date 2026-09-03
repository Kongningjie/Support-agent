package com.lawrence.supportagent.persistence.record;

import java.time.Instant;

/** MyBatis 使用的异步任务数据记录，与领域聚合保持隔离。 */
public class AsyncTaskDO {
    /** 异步任务内部自增主键。 */
    public Long id;
    /** 任务类型枚举名称。 */
    public String taskType;
    /** 关联聚合类型枚举名称。 */
    public String aggregateType;
    /** 关联业务对象内部主键。 */
    public long aggregateId;
    /** 任务创建时关联聚合的版本。 */
    public long aggregateVersion;
    /** 内部任务创建幂等键。 */
    public String idempotencyKey;
    /** 任务执行状态枚举名称。 */
    public String status;
    /** 已经开始执行的次数。 */
    public int attemptCount;
    /** 最大允许执行次数。 */
    public int maxAttempts;
    /** 下一次允许调度的 UTC 时间。 */
    public Instant nextRunAt;
    /** 当前持有执行锁的实例标识。 */
    public String lockedBy;
    /** 任务锁租约的截止时间。 */
    public Instant lockedUntil;
    /** 最后一次失败的稳定错误码。 */
    public String lastErrorCode;
    /** 最后一次失败的脱敏摘要。 */
    public String lastErrorMessage;
    /** 人工重试依据的原任务主键。 */
    public Long retryOfTaskId;
    /** 人工发起重试的原因。 */
    public String manualRetryReason;
    /** 创建操作者或系统身份。 */
    public String createdBy;
    /** 创建 UTC 时间。 */
    public Instant createdAt;
    /** 首次开始执行的 UTC 时间。 */
    public Instant startedAt;
    /** 最终结束的 UTC 时间。 */
    public Instant finishedAt;
    /** 最近状态更新的 UTC 时间。 */
    public Instant updatedAt;
}
