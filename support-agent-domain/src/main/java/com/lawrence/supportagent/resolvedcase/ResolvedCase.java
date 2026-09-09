package com.lawrence.supportagent.resolvedcase;

import com.lawrence.supportagent.sharedkernel.DomainAssertions;
import java.time.Instant;

/** 已解决案例聚合，确保发布内容来自人工确认的工单事实。 */
public record ResolvedCase(Long id, long sourceTicketId, String title, String problem,
                           String cause, String solution, ResolvedCaseStatus status,
                           String contentHash, long version, String publishFailureReason,
                           String rejectionReason, String archiveReason, boolean deleted,
                           String deletedBy, Instant deletedAt, String createdBy,
                           Instant createdAt, String updatedBy, Instant updatedAt,
                           String publishedBy, Instant publishedAt, String archivedBy,
                           Instant archivedAt) {
    /** 校验案例核心事实、状态及审计信息。 */
    public ResolvedCase {
        if (sourceTicketId <= 0 || status == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("案例来源、状态和审计时间不能为空");
        }
        title = DomainAssertions.requiredText(title, "案例标题");
        problem = DomainAssertions.requiredText(problem, "问题现象");
        cause = DomainAssertions.requiredText(cause, "根因");
        solution = DomainAssertions.requiredText(solution, "解决方案");
        contentHash = DomainAssertions.requiredText(contentHash, "内容哈希");
        createdBy = DomainAssertions.requiredText(createdBy, "创建人");
        updatedBy = DomainAssertions.requiredText(updatedBy, "更新人");
        DomainAssertions.version(version);
    }

    /** 创建等待人工审核的案例草稿。 */
    public static ResolvedCase draft(long sourceTicketId, String title, String problem,
                                     String cause, String solution, String contentHash,
                                     String operator, Instant now) {
        return new ResolvedCase(null, sourceTicketId, title, problem, cause, solution,
                ResolvedCaseStatus.DRAFT, contentHash, 0, null, null, null,
                false, null, null, operator, now, operator, now,
                null, null, null, null);
    }

    /** 由人工审核人修改草稿或发布失败案例的完整内容。 */
    public ResolvedCase revise(String newTitle, String newProblem, String newCause,
                               String newSolution, String newContentHash,
                               String operator, Instant now) {
        DomainAssertions.state(status == ResolvedCaseStatus.DRAFT
                || status == ResolvedCaseStatus.PUBLISH_FAILED, "当前案例不能修改");
        return new ResolvedCase(id, sourceTicketId, newTitle, newProblem, newCause,
                newSolution, ResolvedCaseStatus.DRAFT, newContentHash, version + 1,
                null, null, null, deleted, deletedBy, deletedAt, createdBy, createdAt,
                operator, now, null, null, null, null);
    }

    /** 开始把人工审核后的案例写入知识索引。 */
    public ResolvedCase startPublishing(String operator, Instant now) {
        DomainAssertions.state(status == ResolvedCaseStatus.DRAFT
                || status == ResolvedCaseStatus.PUBLISH_FAILED, "当前案例不能发布");
        return copy(ResolvedCaseStatus.PUBLISHING, operator, now, null, null, null,
                publishedBy, publishedAt, null, null);
    }

    /** 标记案例全部分块已经发布成功。 */
    public ResolvedCase publish(String operator, Instant now) {
        DomainAssertions.state(status == ResolvedCaseStatus.PUBLISHING, "只有发布中案例可以完成发布");
        return copy(ResolvedCaseStatus.PUBLISHED, operator, now, null, null, null,
                operator, now, null, null);
    }

    /** 标记案例发布任务最终失败。 */
    public ResolvedCase failPublishing(String reason, String operator, Instant now) {
        DomainAssertions.state(status == ResolvedCaseStatus.PUBLISHING, "只有发布中案例可以标记失败");
        return copy(ResolvedCaseStatus.PUBLISH_FAILED, operator, now,
                DomainAssertions.requiredText(reason, "发布失败原因"), null, null,
                publishedBy, publishedAt, null, null);
    }

    /** 拒绝尚未发布的案例草稿。 */
    public ResolvedCase reject(String reason, String operator, Instant now) {
        DomainAssertions.state(status == ResolvedCaseStatus.DRAFT, "只有案例草稿可以拒绝");
        return copy(ResolvedCaseStatus.REJECTED, operator, now, null,
                DomainAssertions.requiredText(reason, "拒绝原因"), null, null, null, null, null);
    }

    /** 归档已发布案例并记录人工原因。 */
    public ResolvedCase archive(String reason, String operator, Instant now) {
        DomainAssertions.state(status == ResolvedCaseStatus.PUBLISHED, "只有已发布案例可以归档");
        return copy(ResolvedCaseStatus.ARCHIVED, operator, now, null, null,
                DomainAssertions.requiredText(reason, "归档原因"), publishedBy, publishedAt, operator, now);
    }

    /** 复制案例并统一递增版本和更新审计字段。 */
    private ResolvedCase copy(ResolvedCaseStatus newStatus, String operator, Instant now,
                              String failure, String rejection, String archive,
                              String newPublishedBy, Instant newPublishedAt,
                              String newArchivedBy, Instant newArchivedAt) {
        return new ResolvedCase(id, sourceTicketId, title, problem, cause, solution, newStatus,
                contentHash, version + 1, failure, rejection, archive, deleted, deletedBy,
                deletedAt, createdBy, createdAt, operator, now, newPublishedBy,
                newPublishedAt, newArchivedBy, newArchivedAt);
    }
}
