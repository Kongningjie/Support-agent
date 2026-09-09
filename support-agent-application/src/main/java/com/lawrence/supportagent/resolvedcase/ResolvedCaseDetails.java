package com.lawrence.supportagent.resolvedcase;

import com.lawrence.supportagent.ticket.Ticket;
import java.time.Instant;

/**
 * 提供给接口层的案例详情及来源工单摘要。
 *
 * @param caseId 案例内部主键
 * @param sourceTicketNo 来源工单稳定编号
 * @param sourceTicketTitle 来源工单原始标题
 * @param title AI 整理后可由人工修改的案例标题
 * @param problem AI 整理后可由人工修改的问题描述
 * @param cause 从工单根因复制且可由人工修改的案例根因
 * @param solution 从工单方案复制且可由人工修改的解决方案
 * @param status 案例生命周期状态
 * @param version 乐观锁版本及当前知识版本依据
 * @param publishFailureReason 最终发布失败的脱敏原因
 * @param rejectionReason 人工拒绝沉淀的原因
 * @param archiveReason 已发布案例退出检索的原因
 * @param createdAt 案例创建 UTC 时间
 * @param updatedAt 案例最近更新 UTC 时间
 * @param publishedAt 案例成功发布 UTC 时间
 * @param archivedAt 案例归档 UTC 时间
 */
public record ResolvedCaseDetails(long caseId, String sourceTicketNo, String sourceTicketTitle,
                                  String title, String problem, String cause, String solution,
                                  ResolvedCaseStatus status, long version,
                                  String publishFailureReason, String rejectionReason,
                                  String archiveReason, Instant createdAt, Instant updatedAt,
                                  Instant publishedAt, Instant archivedAt) {
    /** 从案例和来源工单创建不暴露内部工单主键的详情。 */
    public static ResolvedCaseDetails from(ResolvedCase value, Ticket ticket) {
        return new ResolvedCaseDetails(value.id(), ticket.ticketNo(), ticket.title(),
                value.title(), value.problem(), value.cause(), value.solution(), value.status(),
                value.version(), value.publishFailureReason(), value.rejectionReason(),
                value.archiveReason(), value.createdAt(), value.updatedAt(),
                value.publishedAt(), value.archivedAt());
    }
}
