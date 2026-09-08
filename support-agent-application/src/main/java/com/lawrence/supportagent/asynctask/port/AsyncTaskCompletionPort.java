package com.lawrence.supportagent.asynctask.port;

import com.lawrence.supportagent.asynctask.AsyncTaskBusinessMutation;
import com.lawrence.supportagent.asynctask.AsyncTaskStatus;
import java.time.Instant;

/** 定义受 Worker 租约保护的异步任务终态事务边界。 */
public interface AsyncTaskCompletionPort {
    /** 在同一事务内执行业务成功动作并把任务标记为成功。 */
    void complete(long taskId, String workerId, Instant now,
                  AsyncTaskBusinessMutation businessMutation);

    /** 保存重试或死亡结果，并在死亡时原子执行最终失败业务动作。 */
    void fail(long taskId, String workerId, AsyncTaskStatus status, Instant nextRunAt,
              String errorCode, String errorMessage, Instant finishedAt, Instant now,
              AsyncTaskBusinessMutation finalFailureMutation);

    /** 在同一事务内执行失效业务动作并取消当前持锁任务。 */
    void cancel(long taskId, String workerId, Instant now,
                AsyncTaskBusinessMutation businessMutation);
}
