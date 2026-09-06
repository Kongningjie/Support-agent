package com.lawrence.supportagent.ticket;

import java.time.Instant;

/**
 * 工单对外查询视图，不包含 MySQL 内部主键。
 *
 * @param ticketNo 稳定对外工单编号
 * @param title 工单标题
 * @param problemDescription 问题现象与背景
 * @param attemptedActions 已尝试操作，可为空
 * @param status 当前工单状态
 * @param rootCause 人工确认根因，仅解决后有值
 * @param solution 人工确认方案，仅解决后有值
 * @param closeReason 未解决关闭原因，仅关闭后有值
 * @param version 当前乐观锁版本
 * @param createdAt 创建 UTC 时间
 * @param updatedAt 最近更新 UTC 时间
 * @param resolvedAt 解决 UTC 时间，可为空
 * @param closedAt 关闭 UTC 时间，可为空
 */
public record TicketDetails(String ticketNo, String title, String problemDescription,
                            String attemptedActions, TicketStatus status, String rootCause,
                            String solution, String closeReason, long version,
                            Instant createdAt, Instant updatedAt, Instant resolvedAt,
                            Instant closedAt) {
    /** 从领域聚合创建不泄露内部主键和操作者信息的查询视图。 */
    public static TicketDetails from(Ticket ticket) {
        return new TicketDetails(ticket.ticketNo(), ticket.title(), ticket.problemDescription(),
                ticket.attemptedActions(), ticket.status(), ticket.rootCause(), ticket.solution(),
                ticket.closeReason(), ticket.version(), ticket.createdAt(), ticket.updatedAt(),
                ticket.resolvedAt(), ticket.closedAt());
    }
}
