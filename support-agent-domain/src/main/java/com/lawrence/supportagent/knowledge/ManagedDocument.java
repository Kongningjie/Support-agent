package com.lawrence.supportagent.knowledge;

import com.lawrence.supportagent.sharedkernel.DomainAssertions;
import java.time.Instant;

/** 托管文档聚合，维护发布、失败、归档和草稿删除规则。 */
public record ManagedDocument(Long id, String title, DocumentInputType inputType,
                              String originalFileName, String mediaType, String rawContent,
                              String contentHash, ManagedDocumentStatus status, long version,
                              String indexFailureReason, String archiveReason, boolean deleted,
                              String deletedBy, Instant deletedAt, String createdBy,
                              Instant createdAt, String updatedBy, Instant updatedAt,
                              String publishedBy, Instant publishedAt, String archivedBy,
                              Instant archivedAt) {
    /** 校验文档的必填内容、状态和审计字段。 */
    public ManagedDocument {
        title = DomainAssertions.requiredText(title, "文档标题");
        rawContent = DomainAssertions.requiredText(rawContent, "文档正文");
        contentHash = DomainAssertions.requiredText(contentHash, "内容哈希");
        if (inputType == null || status == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("文档类型、状态和审计时间不能为空");
        }
        createdBy = DomainAssertions.requiredText(createdBy, "创建人");
        updatedBy = DomainAssertions.requiredText(updatedBy, "更新人");
        DomainAssertions.version(version);
    }

    /** 创建不可检索的托管文档草稿。 */
    public static ManagedDocument draft(String title, DocumentInputType inputType,
                                        String originalFileName, String mediaType,
                                        String content, String hash, String operator, Instant now) {
        return new ManagedDocument(null, title, inputType, originalFileName, mediaType,
                content, hash, ManagedDocumentStatus.DRAFT, 0, null, null, false,
                null, null, operator, now, operator, now, null, null, null, null);
    }

    /** 修改草稿或索引失败文档并恢复为草稿。 */
    public ManagedDocument revise(String newTitle, String newContent, String newHash,
                                  String operator, Instant now) {
        DomainAssertions.state(status == ManagedDocumentStatus.DRAFT
                || status == ManagedDocumentStatus.FAILED, "只有草稿或失败文档可以修改");
        DomainAssertions.state(!deleted, "已删除草稿不能修改");
        return copy(newTitle, newContent, newHash, ManagedDocumentStatus.DRAFT, operator,
                now, null, null, false, null, null, publishedBy, publishedAt, null, null);
    }

    /** 开始异步索引文档。 */
    public ManagedDocument startIndexing(String operator, Instant now) {
        DomainAssertions.state((status == ManagedDocumentStatus.DRAFT
                || status == ManagedDocumentStatus.FAILED) && !deleted, "当前文档不能发布");
        return copy(title, rawContent, contentHash, ManagedDocumentStatus.INDEXING,
                operator, now, null, null, false, null, null, publishedBy, publishedAt, null, null);
    }

    /** 标记全部分块已经成功发布。 */
    public ManagedDocument publish(String operator, Instant now) {
        DomainAssertions.state(status == ManagedDocumentStatus.INDEXING, "只有索引中文档可以发布完成");
        return copy(title, rawContent, contentHash, ManagedDocumentStatus.PUBLISHED,
                operator, now, null, null, false, null, null, operator, now, null, null);
    }

    /** 标记索引任务最终失败并保存脱敏原因。 */
    public ManagedDocument failIndexing(String reason, String operator, Instant now) {
        DomainAssertions.state(status == ManagedDocumentStatus.INDEXING, "只有索引中文档可以标记失败");
        return copy(title, rawContent, contentHash, ManagedDocumentStatus.FAILED,
                operator, now, DomainAssertions.requiredText(reason, "失败原因"), null,
                false, null, null, publishedBy, publishedAt, null, null);
    }

    /** 归档已发布文档并保留审计数据。 */
    public ManagedDocument archive(String reason, String operator, Instant now) {
        DomainAssertions.state(status == ManagedDocumentStatus.PUBLISHED, "只有已发布文档可以归档");
        return copy(title, rawContent, contentHash, ManagedDocumentStatus.ARCHIVED,
                operator, now, null, DomainAssertions.requiredText(reason, "归档原因"),
                false, null, null, publishedBy, publishedAt, operator, now);
    }

    /** 软删除从未发布的草稿。 */
    public ManagedDocument deleteDraft(String operator, Instant now) {
        DomainAssertions.state(status == ManagedDocumentStatus.DRAFT && publishedAt == null,
                "只有从未发布的草稿可以删除");
        return copy(title, rawContent, contentHash, status, operator, now, null, null,
                true, operator, now, null, null, null, null);
    }

    /** 复制文档并统一更新版本和审计信息。 */
    private ManagedDocument copy(String newTitle, String newContent, String newHash,
                                 ManagedDocumentStatus newStatus, String operator, Instant now,
                                 String failure, String archive, boolean newDeleted,
                                 String newDeletedBy, Instant newDeletedAt, String newPublishedBy,
                                 Instant newPublishedAt, String newArchivedBy, Instant newArchivedAt) {
        return new ManagedDocument(id, newTitle, inputType, originalFileName, mediaType,
                newContent, newHash, newStatus, version + 1, failure, archive, newDeleted,
                newDeletedBy, newDeletedAt, createdBy, createdAt, operator, now,
                newPublishedBy, newPublishedAt, newArchivedBy, newArchivedAt);
    }
}
