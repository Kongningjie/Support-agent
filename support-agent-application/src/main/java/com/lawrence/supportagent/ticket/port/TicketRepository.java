package com.lawrence.supportagent.ticket.port;

import com.lawrence.supportagent.ticket.Ticket;
import com.lawrence.supportagent.ticket.TicketStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;

/** 定义工单聚合的持久化边界。 */
public interface TicketRepository {
    /** 按内部主键查询工单。 */
    Optional<Ticket> findById(long id);

    /** 按稳定对外编号查询工单。 */
    Optional<Ticket> findByTicketNo(String ticketNo);

    /** 按用户范围查询工单；管理员范围可以包含历史无归属工单。 */
    Optional<Ticket> findByTicketNoForAccess(String ticketNo, UUID ownerUserId, boolean allTickets);

    /** 新增或更新工单并返回持久化后的聚合。 */
    Ticket save(Ticket ticket);

    /** 为刚插入的工单设置一次性对外编号。 */
    Ticket assignNumber(Ticket ticket, String ticketNo);

    /** 按固定排序和受控条件查询一页工单。 */
    List<Ticket> findPage(TicketStatus status, String keyword, Set<UUID> spaceIds, UUID ownerUserId,
                          boolean allTickets, int offset, int size);

    /** 统计受控条件下的工单总数。 */
    long count(TicketStatus status, String keyword, Set<UUID> spaceIds,
               UUID ownerUserId, boolean allTickets);

    /** 兼容既有内部测试的全空间计数；生产授权用例必须使用显式空间重载。 */
    default long count(TicketStatus status, String keyword, UUID ownerUserId,
                       boolean allTickets) {
        return count(status, keyword, Set.of(), ownerUserId, allTickets);
    }
}
