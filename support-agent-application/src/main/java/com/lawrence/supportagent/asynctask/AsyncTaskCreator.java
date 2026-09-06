package com.lawrence.supportagent.asynctask;

import com.lawrence.supportagent.asynctask.port.AsyncTaskRepository;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;

/** 为后续业务用例提供事务内创建持久化异步任务的统一入口。 */
public class AsyncTaskCreator {
    private final AsyncTaskRepository repository;
    private final TimeProvider timeProvider;

    /** 注入任务仓储和统一时间端口。 */
    public AsyncTaskCreator(AsyncTaskRepository repository, TimeProvider timeProvider) {
        this.repository = repository;
        this.timeProvider = timeProvider;
    }

    /** 创建具有固定三次尝试上限的待执行任务。 */
    public AsyncTask create(AsyncTaskType taskType, AggregateType aggregateType,
                            long aggregateId, long aggregateVersion,
                            String internalIdempotencyKey, String createdBy) {
        return repository.save(AsyncTask.pending(taskType, aggregateType, aggregateId,
                aggregateVersion, internalIdempotencyKey, createdBy, timeProvider.now()));
    }
}
