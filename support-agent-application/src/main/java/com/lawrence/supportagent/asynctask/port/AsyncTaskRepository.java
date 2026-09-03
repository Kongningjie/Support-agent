package com.lawrence.supportagent.asynctask.port;

import com.lawrence.supportagent.asynctask.AsyncTask;
import java.util.Optional;

/** 定义异步任务聚合的持久化边界。 */
public interface AsyncTaskRepository {
    /** 按内部主键查询异步任务。 */
    Optional<AsyncTask> findById(long id);

    /** 新增或更新任务并返回持久化后的聚合。 */
    AsyncTask save(AsyncTask task);
}
