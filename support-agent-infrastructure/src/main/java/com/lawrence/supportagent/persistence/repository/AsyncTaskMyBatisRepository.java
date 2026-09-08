package com.lawrence.supportagent.persistence.repository;

import com.lawrence.supportagent.asynctask.AsyncTask;
import com.lawrence.supportagent.asynctask.AsyncTaskStatus;
import com.lawrence.supportagent.asynctask.AsyncTaskType;
import com.lawrence.supportagent.asynctask.AggregateType;
import com.lawrence.supportagent.asynctask.port.AsyncTaskRepository;
import com.lawrence.supportagent.persistence.mapper.FoundationMapper;
import com.lawrence.supportagent.persistence.mapper.AsyncTaskWorkflowMapper;
import com.lawrence.supportagent.persistence.record.AggregateRecordMapper;
import com.lawrence.supportagent.persistence.record.AsyncTaskDO;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 使用 MyBatis XML 持久化异步任务聚合。 */
@Repository
public class AsyncTaskMyBatisRepository implements AsyncTaskRepository {
    private final FoundationMapper mapper;
    private final AsyncTaskWorkflowMapper workflowMapper;

    /** 注入异步任务 Mapper。 */
    public AsyncTaskMyBatisRepository(FoundationMapper mapper,
                                      AsyncTaskWorkflowMapper workflowMapper) {
        this.mapper = mapper;
        this.workflowMapper = workflowMapper;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AsyncTask> findById(long id) {
        return Optional.ofNullable(mapper.findTask(id)).map(AggregateRecordMapper::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public AsyncTask save(AsyncTask value) {
        AsyncTaskDO record = AggregateRecordMapper.toRecord(value);
        int changed;
        try {
            changed = record.id == null ? mapper.insertTask(record) : mapper.updateTask(record);
        } catch (DuplicateKeyException exception) {
            return replayDuplicateCreation(value, exception);
        }
        if (changed != 1) {
            throw new IllegalStateException("异步任务持久化失败");
        }
        return AggregateRecordMapper.toDomain(record);
    }

    /** {@inheritDoc} */
    @Override
    public List<AsyncTask> findPage(AsyncTaskType taskType, AsyncTaskStatus status,
                                    AggregateType aggregateType, Long aggregateId,
                                    int offset, int size) {
        return workflowMapper.findPage(name(taskType), name(status), name(aggregateType),
                        aggregateId, offset, size).stream()
                .map(AggregateRecordMapper::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public long count(AsyncTaskType taskType, AsyncTaskStatus status,
                      AggregateType aggregateType, Long aggregateId) {
        return workflowMapper.count(name(taskType), name(status), name(aggregateType), aggregateId);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public List<AsyncTask> claimDue(String workerId, Instant now,
                                    Instant lockedUntil, int limit) {
        List<Long> ids = workflowMapper.findClaimableIds(now, limit);
        return ids.stream().filter(id -> workflowMapper.claim(id, workerId, lockedUntil, now) == 1)
                .map(mapper::findTask).map(AggregateRecordMapper::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public boolean renewLease(long taskId, String workerId, Instant lockedUntil, Instant now) {
        return workflowMapper.renewLease(taskId, workerId, lockedUntil, now) == 1;
    }

    /** {@inheritDoc} */
    @Override
    public boolean complete(long taskId, String workerId, Instant now) {
        return workflowMapper.complete(taskId, workerId, now) == 1;
    }

    /** {@inheritDoc} */
    @Override
    public boolean fail(long taskId, String workerId, AsyncTaskStatus status,
                        Instant nextRunAt, String errorCode, String errorMessage,
                        Instant finishedAt, Instant now) {
        return workflowMapper.fail(taskId, workerId, status.name(), nextRunAt,
                errorCode, errorMessage, finishedAt, now) == 1;
    }

    /** {@inheritDoc} */
    @Override
    public boolean cancel(long taskId, Instant now) {
        return workflowMapper.cancel(taskId, now) == 1;
    }

    /** 返回可空枚举的稳定名称。 */
    private String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /** 仅当唯一键对应任务身份完全一致时，把并发重复创建视为成功重放。 */
    private AsyncTask replayDuplicateCreation(AsyncTask requested, DuplicateKeyException exception) {
        if (requested.id() != null) {
            throw exception;
        }
        AsyncTaskDO record = workflowMapper.findByIdempotencyKey(requested.idempotencyKey());
        if (record == null) {
            throw exception;
        }
        AsyncTask existing = AggregateRecordMapper.toDomain(record);
        if (existing.taskType() != requested.taskType()
                || existing.aggregateType() != requested.aggregateType()
                || existing.aggregateId() != requested.aggregateId()
                || existing.aggregateVersion() != requested.aggregateVersion()) {
            throw new IllegalStateException("内部任务幂等键被用于不同任务", exception);
        }
        return existing;
    }
}
