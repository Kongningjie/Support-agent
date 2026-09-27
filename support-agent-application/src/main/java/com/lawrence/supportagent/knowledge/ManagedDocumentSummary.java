package com.lawrence.supportagent.knowledge;

import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceSummary;
import java.time.Instant;
import java.util.UUID;

/**
 * 不含正文的托管文档分页摘要。
 *
 * @param id 文档内部主键
 * @param spaceId 文档归属的知识空间 UUID
 * @param space 已授权读取的知识空间最小摘要
 * @param title 文档标题
 * @param inputType 文档原始录入方式
 * @param status 文档生命周期状态
 * @param version 当前乐观锁版本
 * @param createdAt 创建 UTC 时间
 * @param updatedAt 最近更新 UTC 时间
 * @param publishedAt 成功发布 UTC 时间，可为空
 */
public record ManagedDocumentSummary(long id, UUID spaceId, KnowledgeSpaceSummary space,
                                     String title, DocumentInputType inputType,
                                     ManagedDocumentStatus status, long version,
                                     Instant createdAt, Instant updatedAt, Instant publishedAt) {
    /** 从领域聚合创建安全分页摘要。 */
    public static ManagedDocumentSummary from(ManagedDocument value,
                                              KnowledgeSpaceSummary space) {
        return new ManagedDocumentSummary(value.id(), value.spaceId(),
                space,
                value.title(), value.inputType(),
                value.status(), value.version(), value.createdAt(), value.updatedAt(),
                value.publishedAt());
    }

    /** 兼容阶段 18 GLOBAL 数据的摘要工厂。 */
    public static ManagedDocumentSummary from(ManagedDocument value) {
        return from(value, KnowledgeSpaceSummary.global(value.spaceId()));
    }
}
