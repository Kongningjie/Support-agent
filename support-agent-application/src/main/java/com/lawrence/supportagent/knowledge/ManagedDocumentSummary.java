package com.lawrence.supportagent.knowledge;

import java.time.Instant;

/** 不含正文的托管文档分页摘要。 */
public record ManagedDocumentSummary(long id, String title, DocumentInputType inputType,
                                     ManagedDocumentStatus status, long version,
                                     Instant createdAt, Instant updatedAt, Instant publishedAt) {
    /** 从领域聚合创建安全分页摘要。 */
    public static ManagedDocumentSummary from(ManagedDocument value) {
        return new ManagedDocumentSummary(value.id(), value.title(), value.inputType(),
                value.status(), value.version(), value.createdAt(), value.updatedAt(),
                value.publishedAt());
    }
}
