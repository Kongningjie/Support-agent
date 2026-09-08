package com.lawrence.supportagent.asynctask;

import com.lawrence.supportagent.asynctask.port.AsyncTaskCompletionPort;
import com.lawrence.supportagent.persistence.mapper.AsyncTaskWorkflowMapper;
import java.time.Instant;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 使用单个 MySQL 事务原子提交业务变更与异步任务终态。 */
@Repository
public class TransactionalAsyncTaskCompletionAdapter implements AsyncTaskCompletionPort {
    private final AsyncTaskWorkflowMapper mapper;

    /** 注入任务条件更新 Mapper。 */
    public TransactionalAsyncTaskCompletionAdapter(AsyncTaskWorkflowMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void complete(long taskId, String workerId, Instant now,
                         AsyncTaskBusinessMutation businessMutation) {
        requireChanged(mapper.complete(taskId, workerId, now));
        businessMutation.apply();
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void fail(long taskId, String workerId, AsyncTaskStatus status,
                     Instant nextRunAt, String errorCode, String errorMessage,
                     Instant finishedAt, Instant now,
                     AsyncTaskBusinessMutation finalFailureMutation) {
        requireChanged(mapper.fail(taskId, workerId, status.name(), nextRunAt,
                errorCode, errorMessage, finishedAt, now));
        if (status == AsyncTaskStatus.DEAD) {
            finalFailureMutation.apply();
        }
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void cancel(long taskId, String workerId, Instant now,
                       AsyncTaskBusinessMutation businessMutation) {
        requireChanged(mapper.cancelOwned(taskId, workerId, now));
        businessMutation.apply();
    }

    /** 租约围栏未命中时抛出异常，使同事务业务修改回滚。 */
    private void requireChanged(int changed) {
        if (changed != 1) {
            throw new IllegalStateException("异步任务租约已失效");
        }
    }
}
