package com.lawrence.supportagent.persistence.repository;

import com.lawrence.supportagent.persistence.mapper.FoundationMapper;
import com.lawrence.supportagent.persistence.mapper.TicketWorkflowMapper;
import com.lawrence.supportagent.persistence.record.AggregateRecordMapper;
import com.lawrence.supportagent.persistence.record.TicketDO;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.ticket.Ticket;
import com.lawrence.supportagent.ticket.TicketStatus;
import com.lawrence.supportagent.ticket.port.TicketRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 使用 MyBatis XML 持久化工单聚合。 */
@Repository
public class TicketMyBatisRepository implements TicketRepository {
    private final FoundationMapper mapper;
    private final TicketWorkflowMapper workflowMapper;

    /** 注入工单 Mapper。 */
    public TicketMyBatisRepository(FoundationMapper mapper, TicketWorkflowMapper workflowMapper) {
        this.mapper = mapper;
        this.workflowMapper = workflowMapper;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Ticket> findById(long id) {
        return Optional.ofNullable(mapper.findTicket(id)).map(AggregateRecordMapper::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Ticket> findByTicketNo(String ticketNo) {
        return Optional.ofNullable(workflowMapper.findByTicketNo(ticketNo))
                .map(AggregateRecordMapper::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public Ticket save(Ticket value) {
        TicketDO record = AggregateRecordMapper.toRecord(value);
        int changed = record.id == null ? mapper.insertTicket(record) : mapper.updateTicket(record);
        if (changed != 1) {
            if (record.id != null) {
                throw new ApplicationException(ErrorCode.TICKET_VERSION_CONFLICT, "工单版本已变化");
            }
            throw new IllegalStateException("工单创建失败");
        }
        return AggregateRecordMapper.toDomain(record);
    }

    /** {@inheritDoc} */
    @Override
    public Ticket assignNumber(Ticket ticket, String ticketNo) {
        if (ticket.id() == null || workflowMapper.assignNumber(ticket.id(), ticketNo) != 1) {
            throw new IllegalStateException("工单编号分配失败");
        }
        return ticket.assignNumber(ticket.id(), ticketNo);
    }

    /** {@inheritDoc} */
    @Override
    public List<Ticket> findPage(TicketStatus status, String keyword, int offset, int size) {
        return workflowMapper.findPage(status == null ? null : status.name(), keyword, offset, size)
                .stream().map(AggregateRecordMapper::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public long count(TicketStatus status, String keyword) {
        return workflowMapper.count(status == null ? null : status.name(), keyword);
    }
}
