package com.lawrence.supportagent.persistence.repository;

import com.lawrence.supportagent.persistence.mapper.FoundationMapper;
import com.lawrence.supportagent.persistence.mapper.ResolvedCaseWorkflowMapper;
import com.lawrence.supportagent.persistence.record.AggregateRecordMapper;
import com.lawrence.supportagent.persistence.record.ResolvedCaseDO;
import com.lawrence.supportagent.resolvedcase.ResolvedCase;
import com.lawrence.supportagent.resolvedcase.ResolvedCaseStatus;
import com.lawrence.supportagent.resolvedcase.port.ResolvedCaseRepository;
import java.util.Optional;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 使用 MyBatis XML 持久化已解决案例聚合。 */
@Repository
public class ResolvedCaseMyBatisRepository implements ResolvedCaseRepository {
    private final FoundationMapper mapper;
    private final ResolvedCaseWorkflowMapper workflowMapper;

    /** 注入已解决案例 Mapper。 */
    public ResolvedCaseMyBatisRepository(FoundationMapper mapper,
                                         ResolvedCaseWorkflowMapper workflowMapper) {
        this.mapper = mapper;
        this.workflowMapper = workflowMapper;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ResolvedCase> findById(long id) {
        return Optional.ofNullable(mapper.findCase(id)).map(AggregateRecordMapper::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ResolvedCase> findBySourceTicketId(long sourceTicketId) {
        return Optional.ofNullable(workflowMapper.findBySourceTicketId(sourceTicketId))
                .map(AggregateRecordMapper::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public ResolvedCase save(ResolvedCase value) {
        ResolvedCaseDO record = AggregateRecordMapper.toRecord(value);
        int changed = record.id == null ? mapper.insertCase(record) : mapper.updateCase(record);
        if (changed != 1) {
            throw new IllegalStateException("已解决案例持久化版本冲突");
        }
        return AggregateRecordMapper.toDomain(record);
    }

    /** {@inheritDoc} */
    @Override
    public List<ResolvedCase> findPage(ResolvedCaseStatus status, String sourceTicketNo,
                                       String keyword, int offset, int size) {
        return workflowMapper.findPage(status == null ? null : status.name(), sourceTicketNo,
                        keyword, offset, size).stream()
                .map(AggregateRecordMapper::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public long count(ResolvedCaseStatus status, String sourceTicketNo, String keyword) {
        return workflowMapper.count(status == null ? null : status.name(), sourceTicketNo, keyword);
    }
}
