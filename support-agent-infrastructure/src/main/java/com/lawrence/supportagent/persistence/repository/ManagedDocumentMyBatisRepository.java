package com.lawrence.supportagent.persistence.repository;

import com.lawrence.supportagent.knowledge.ManagedDocument;
import com.lawrence.supportagent.knowledge.port.ManagedDocumentRepository;
import com.lawrence.supportagent.persistence.mapper.FoundationMapper;
import com.lawrence.supportagent.persistence.record.AggregateRecordMapper;
import com.lawrence.supportagent.persistence.record.ManagedDocumentDO;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 使用 MyBatis XML 持久化托管文档聚合。 */
@Repository
public class ManagedDocumentMyBatisRepository implements ManagedDocumentRepository {
    private final FoundationMapper mapper;

    /** 注入文档 Mapper。 */
    public ManagedDocumentMyBatisRepository(FoundationMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ManagedDocument> findById(long id) {
        return Optional.ofNullable(mapper.findDocument(id)).map(AggregateRecordMapper::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public ManagedDocument save(ManagedDocument value) {
        ManagedDocumentDO record = AggregateRecordMapper.toRecord(value);
        int changed = record.id == null
                ? mapper.insertDocument(record)
                : mapper.updateDocument(record);
        if (changed != 1) {
            throw new IllegalStateException("托管文档持久化版本冲突");
        }
        return AggregateRecordMapper.toDomain(record);
    }
}
