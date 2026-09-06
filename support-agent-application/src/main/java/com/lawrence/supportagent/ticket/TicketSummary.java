package com.lawrence.supportagent.ticket;

import java.time.Instant;

/**
 * 工单分页列表摘要，不返回大文本和内部主键。
 *
 * @param ticketNo 稳定对外工单编号
 * @param title 工单标题
 * @param status 当前状态
 * @param version 当前乐观锁版本
 * @param createdAt 创建 UTC 时间
 * @param updatedAt 最近更新 UTC 时间
 */
public record TicketSummary(String ticketNo, String title, TicketStatus status, long version,
                            Instant createdAt, Instant updatedAt) {
    /** 从领域聚合创建分页摘要。 */
    public static TicketSummary from(Ticket ticket) {
        return new TicketSummary(ticket.ticketNo(), ticket.title(), ticket.status(),
                ticket.version(), ticket.createdAt(), ticket.updatedAt());
    }
}
