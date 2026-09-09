package com.lawrence.supportagent.resolvedcase;

import java.time.Instant;

/**
 * 案例分页列表使用的不含完整正文摘要。
 *
 * @param caseId 案例内部主键
 * @param sourceTicketNo 来源工单稳定编号
 * @param title 案例标题
 * @param status 案例生命周期状态
 * @param version 当前乐观锁版本
 * @param createdAt 案例创建 UTC 时间
 * @param updatedAt 案例最近更新 UTC 时间
 */
public record ResolvedCaseSummary(long caseId, String sourceTicketNo, String title,
                                  ResolvedCaseStatus status, long version,
                                  Instant createdAt, Instant updatedAt) {
}
