package com.lawrence.supportagent.ticket.port;

import com.lawrence.supportagent.ticket.Ticket;
import java.util.Optional;

/** 定义工单聚合的持久化边界。 */
public interface TicketRepository {
    /** 按内部主键查询工单。 */
    Optional<Ticket> findById(long id);

    /** 新增或更新工单并返回持久化后的聚合。 */
    Ticket save(Ticket ticket);
}
