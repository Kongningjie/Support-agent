package com.lawrence.supportagent.asynctask.port;

import com.lawrence.supportagent.asynctask.AsyncTask;
import com.lawrence.supportagent.asynctask.AsyncTaskStatus;
import com.lawrence.supportagent.asynctask.AsyncTaskType;
import com.lawrence.supportagent.asynctask.AggregateType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 定义异步任务聚合的持久化边界。 */
public interface AsyncTaskRepository {
    /** 按内部主键查询异步任务。 */
    Optional<AsyncTask> findById(long id);

    /** 新增或更新任务并返回持久化后的聚合。 */
    AsyncTask save(AsyncTask task);

    /** 按受控过滤条件和固定排序查询一页任务。 */
    List<AsyncTask> findPage(AsyncTaskType taskType, AsyncTaskStatus status,
                             AggregateType aggregateType, Long aggregateId,
                             int offset, int size);

    /** 统计受控过滤条件下的任务总数。 */
    long count(AsyncTaskType taskType, AsyncTaskStatus status,
               AggregateType aggregateType, Long aggregateId);

    /** 使用短事务和跳锁语义抢占当前可执行任务。 */
    List<AsyncTask> claimDue(String workerId, Instant now, Instant lockedUntil, int limit);

    /** 仅允许当前持锁 Worker 延长运行中任务的租约。 */
    boolean renewLease(long taskId, String workerId, Instant lockedUntil, Instant now);

    /** 仅允许当前持锁 Worker 保存成功终态。 */
    boolean complete(long taskId, String workerId, Instant now);

    /** 仅允许当前持锁 Worker 保存重试等待或死亡结果。 */
    boolean fail(long taskId, String workerId, AsyncTaskStatus status, Instant nextRunAt,
                 String errorCode, String errorMessage, Instant finishedAt, Instant now);

    /** 以条件更新取消非终态任务。 */
    boolean cancel(long taskId, Instant now);
}
