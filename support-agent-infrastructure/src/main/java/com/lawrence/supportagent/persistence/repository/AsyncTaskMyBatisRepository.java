package com.lawrence.supportagent.persistence.repository;

import com.lawrence.supportagent.asynctask.AsyncTask;
import com.lawrence.supportagent.asynctask.port.AsyncTaskRepository;
import com.lawrence.supportagent.persistence.mapper.FoundationMapper;
import com.lawrence.supportagent.persistence.record.AggregateRecordMapper;
import com.lawrence.supportagent.persistence.record.AsyncTaskDO;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 使用 MyBatis XML 持久化异步任务聚合。 */
@Repository
public class AsyncTaskMyBatisRepository implements AsyncTaskRepository {
    private final FoundationMapper mapper;

    /** 注入异步任务 Mapper。 */
    public AsyncTaskMyBatisRepository(FoundationMapper mapper) {
        this.mapper = mapper;
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
        int changed = record.id == null ? mapper.insertTask(record) : mapper.updateTask(record);
        if (changed != 1) {
            throw new IllegalStateException("异步任务持久化失败");
        }
        return AggregateRecordMapper.toDomain(record);
    }
}
