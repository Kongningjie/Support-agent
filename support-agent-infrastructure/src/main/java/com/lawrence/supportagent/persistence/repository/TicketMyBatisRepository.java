package com.lawrence.supportagent.persistence.repository;

import com.lawrence.supportagent.persistence.mapper.FoundationMapper;
import com.lawrence.supportagent.persistence.record.AggregateRecordMapper;
import com.lawrence.supportagent.persistence.record.TicketDO;
import com.lawrence.supportagent.ticket.Ticket;
import com.lawrence.supportagent.ticket.port.TicketRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 使用 MyBatis XML 持久化工单聚合。 */
@Repository
public class TicketMyBatisRepository implements TicketRepository {
    private final FoundationMapper mapper;

    /** 注入工单 Mapper。 */
    public TicketMyBatisRepository(FoundationMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Ticket> findById(long id) {
        return Optional.ofNullable(mapper.findTicket(id)).map(AggregateRecordMapper::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public Ticket save(Ticket value) {
        TicketDO record = AggregateRecordMapper.toRecord(value);
        int changed = record.id == null ? mapper.insertTicket(record) : mapper.updateTicket(record);
        if (changed != 1) {
            throw new IllegalStateException("工单持久化版本冲突");
        }
        return AggregateRecordMapper.toDomain(record);
    }
}
