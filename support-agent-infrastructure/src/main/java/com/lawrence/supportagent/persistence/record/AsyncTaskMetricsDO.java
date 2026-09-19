package com.lawrence.supportagent.persistence.record;

import java.time.Instant;

/** 承载 Outbox 运行指标查询结果，不进入领域模型或公共接口。 */
public class AsyncTaskMetricsDO {
    /** 尚未进入终态的任务数量，包含等待、重试等待和运行中任务。 */
    public long backlog;
    /** 尚未进入终态任务中最早的创建时间；无积压时为空。 */
    public Instant oldestCreatedAt;
    /** 当前表内任务累计发生的额外尝试次数。 */
    public long retryAttempts;
    /** 当前处于 DEAD 终态的任务数量。 */
    public long dead;
    /** 指定统计窗口内成功完成的任务数量。 */
    public long throughput;
}
