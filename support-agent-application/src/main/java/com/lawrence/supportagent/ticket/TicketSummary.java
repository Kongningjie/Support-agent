package com.lawrence.supportagent.ticket;

import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceSummary;
import java.time.Instant;
import java.util.UUID;

/**
 * 工单分页列表摘要，不返回大文本和内部主键。
 *
 * @param ticketNo 稳定对外工单编号
 * @param spaceId 工单归属的知识空间 UUID
 * @param space 已授权读取的知识空间最小摘要
 * @param title 工单标题
 * @param status 当前状态
 * @param version 当前乐观锁版本
 * @param createdAt 创建 UTC 时间
 * @param updatedAt 最近更新 UTC 时间
 */
public record TicketSummary(String ticketNo, UUID spaceId, KnowledgeSpaceSummary space,
                            String title, TicketStatus status, long version,
                            Instant createdAt, Instant updatedAt) {
    /** 从领域聚合创建分页摘要。 */
    public static TicketSummary from(Ticket ticket, KnowledgeSpaceSummary space) {
        return new TicketSummary(ticket.ticketNo(), ticket.spaceId(),
                space,
                ticket.title(), ticket.status(),
                ticket.version(), ticket.createdAt(), ticket.updatedAt());
    }

    /** 为内部 GLOBAL 路径保留兼容工厂。 */
    public static TicketSummary from(Ticket ticket) {
        return from(ticket, KnowledgeSpaceSummary.global(ticket.spaceId()));
    }
}
