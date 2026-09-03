package com.lawrence.supportagent.ticket;

import com.lawrence.supportagent.sharedkernel.DomainAssertions;
import java.time.Instant;
import java.util.UUID;

/** 工单聚合，集中维护内容、审计字段和状态迁移规则。 */
public record Ticket(Long id, String ticketNo, UUID conversationId, UUID sourceTurnId,
                     String title, String problemDescription, String attemptedActions,
                     TicketStatus status, String rootCause, String solution, String closeReason,
                     long version, String createdBy, Instant createdAt, String updatedBy,
                     Instant updatedAt, String resolvedBy, Instant resolvedAt,
                     String closedBy, Instant closedAt) {
    /** 校验持久化或新建工单必须满足的领域不变量。 */
    public Ticket {
        title = DomainAssertions.requiredText(title, "工单标题");
        problemDescription = DomainAssertions.requiredText(problemDescription, "问题描述");
        if (status == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("工单状态和审计时间不能为空");
        }
        createdBy = DomainAssertions.requiredText(createdBy, "创建人");
        updatedBy = DomainAssertions.requiredText(updatedBy, "更新人");
        DomainAssertions.version(version);
    }

    /** 创建尚未分配数据库主键和工单号的草稿。 */
    public static Ticket draft(UUID conversationId, UUID sourceTurnId, String title,
                               String problemDescription, String attemptedActions,
                               String operator, Instant now) {
        return new Ticket(null, null, conversationId, sourceTurnId, title, problemDescription,
                attemptedActions, TicketStatus.DRAFT, null, null, null, 0,
                operator, now, operator, now, null, null, null, null);
    }

    /** 修改草稿内容并递增版本。 */
    public Ticket reviseDraft(String newTitle, String newProblemDescription,
                              String newAttemptedActions, String operator, Instant now) {
        requireStatus(TicketStatus.DRAFT, "只有草稿工单可以修改");
        return new Ticket(id, ticketNo, conversationId, sourceTurnId, newTitle,
                newProblemDescription, newAttemptedActions, status, null, null, null,
                version + 1, createdBy, createdAt, operator, now, null, null, null, null);
    }

    /** 将草稿提交为待处理工单。 */
    public Ticket submit(String operator, Instant now) {
        requireStatus(TicketStatus.DRAFT, "只有草稿工单可以提交");
        return copy(TicketStatus.OPEN, null, null, null, operator, now, null, null, null, null);
    }

    /** 使用人工确认的根因和方案解决开放工单。 */
    public Ticket resolve(String confirmedRootCause, String confirmedSolution,
                          String operator, Instant now) {
        requireStatus(TicketStatus.OPEN, "只有开放工单可以解决");
        return copy(TicketStatus.RESOLVED,
                DomainAssertions.requiredText(confirmedRootCause, "根因"),
                DomainAssertions.requiredText(confirmedSolution, "解决方案"), null,
                operator, now, operator, now, null, null);
    }

    /** 关闭草稿或开放工单并记录人工原因。 */
    public Ticket close(String reason, String operator, Instant now) {
        DomainAssertions.state(status == TicketStatus.DRAFT || status == TicketStatus.OPEN,
                "只有草稿或开放工单可以关闭");
        return copy(TicketStatus.CLOSED, null, null,
                DomainAssertions.requiredText(reason, "关闭原因"), operator, now,
                null, null, operator, now);
    }

    /** 校验当前工单处于指定状态。 */
    private void requireStatus(TicketStatus expected, String message) {
        DomainAssertions.state(status == expected, message);
    }

    /** 复制工单并统一更新版本及审计信息。 */
    private Ticket copy(TicketStatus newStatus, String newRootCause, String newSolution,
                        String newCloseReason, String operator, Instant now,
                        String newResolvedBy, Instant newResolvedAt,
                        String newClosedBy, Instant newClosedAt) {
        return new Ticket(id, ticketNo, conversationId, sourceTurnId, title, problemDescription,
                attemptedActions, newStatus, newRootCause, newSolution, newCloseReason,
                version + 1, createdBy, createdAt, operator, now, newResolvedBy,
                newResolvedAt, newClosedBy, newClosedAt);
    }
}
