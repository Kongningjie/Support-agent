package com.lawrence.supportagent.knowledge;

import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceSummary;
import java.time.Instant;
import java.util.UUID;

/** 应用层托管文档详情，保留正文但不暴露持久化实现。 */
public record ManagedDocumentDetails(long id, UUID spaceId, KnowledgeSpaceSummary space,
                                     String title, DocumentInputType inputType,
                                     String originalFileName, String mediaType, String rawContent,
                                     String contentHash, ManagedDocumentStatus status, long version,
                                     String indexFailureReason, String archiveReason,
                                     Instant createdAt, Instant updatedAt,
                                     Instant publishedAt, Instant archivedAt) {
    /** 从未删除的领域聚合创建详情视图。 */
    public static ManagedDocumentDetails from(ManagedDocument value) {
        return new ManagedDocumentDetails(value.id(), value.spaceId(),
                KnowledgeSpaceSummary.global(value.spaceId()),
                value.title(), value.inputType(),
                value.originalFileName(), value.mediaType(), value.rawContent(), value.contentHash(),
                value.status(), value.version(), value.indexFailureReason(), value.archiveReason(),
                value.createdAt(), value.updatedAt(), value.publishedAt(), value.archivedAt());
    }
}
