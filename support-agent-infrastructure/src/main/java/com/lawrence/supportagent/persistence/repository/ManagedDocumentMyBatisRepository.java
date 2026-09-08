package com.lawrence.supportagent.persistence.repository;

import com.lawrence.supportagent.knowledge.ManagedDocument;
import com.lawrence.supportagent.knowledge.ManagedDocumentStatus;
import com.lawrence.supportagent.knowledge.port.ManagedDocumentRepository;
import com.lawrence.supportagent.persistence.mapper.FoundationMapper;
import com.lawrence.supportagent.persistence.mapper.ManagedDocumentWorkflowMapper;
import com.lawrence.supportagent.persistence.record.AggregateRecordMapper;
import com.lawrence.supportagent.persistence.record.ManagedDocumentDO;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/** 使用 MyBatis XML 持久化托管文档聚合。 */
@Repository
public class ManagedDocumentMyBatisRepository implements ManagedDocumentRepository {
    private final FoundationMapper mapper;
    private final ManagedDocumentWorkflowMapper workflowMapper;

    /** 注入文档 Mapper。 */
    public ManagedDocumentMyBatisRepository(FoundationMapper mapper,
                                             ManagedDocumentWorkflowMapper workflowMapper) {
        this.mapper = mapper;
        this.workflowMapper = workflowMapper;
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
        int changed;
        try {
            changed = record.id == null
                    ? mapper.insertDocument(record)
                    : mapper.updateDocument(record);
        } catch (DuplicateKeyException exception) {
            throw new ApplicationException(ErrorCode.KNOWLEDGE_DUPLICATE_CONTENT,
                    "相同内容的有效文档已经存在");
        }
        if (changed != 1) {
            throw new ApplicationException(ErrorCode.KNOWLEDGE_VERSION_CONFLICT,
                    "文档版本已变化");
        }
        return AggregateRecordMapper.toDomain(record);
    }

    /** {@inheritDoc} */
    @Override
    public List<ManagedDocument> findPage(ManagedDocumentStatus status, String keyword,
                                          int offset, int size) {
        String statusName = status == null ? null : status.name();
        return workflowMapper.findPage(statusName, keyword, offset, size).stream()
                .map(AggregateRecordMapper::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public long count(ManagedDocumentStatus status, String keyword) {
        return workflowMapper.count(status == null ? null : status.name(), keyword);
    }

    /** {@inheritDoc} */
    @Override
    public boolean existsActiveContentHash(String contentHash, Long excludedDocumentId) {
        return workflowMapper.countActiveHash(contentHash, excludedDocumentId) > 0;
    }
}
