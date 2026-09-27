package com.lawrence.supportagent.knowledge;

import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceSummary;
import java.time.Instant;
import java.util.UUID;

/**
 * 应用层托管文档详情，保留正文但不暴露持久化实现。
 *
 * @param id 文档内部主键
 * @param spaceId 文档归属的知识空间 UUID
 * @param space 已授权读取的知识空间最小摘要
 * @param title 文档标题
 * @param inputType 文档原始录入方式
 * @param originalFileName 上传来源文件名，非文件录入时可为空
 * @param mediaType 原始内容媒体类型
 * @param rawContent 文档原始正文
 * @param contentHash 原始正文内容哈希
 * @param status 文档生命周期状态
 * @param version 乐观锁版本及当前知识版本依据
 * @param indexFailureReason 最终索引失败的脱敏原因
 * @param archiveReason 文档归档原因
 * @param createdAt 创建 UTC 时间
 * @param updatedAt 最近更新 UTC 时间
 * @param publishedAt 成功发布 UTC 时间，可为空
 * @param archivedAt 归档 UTC 时间，可为空
 */
public record ManagedDocumentDetails(long id, UUID spaceId, KnowledgeSpaceSummary space,
                                     String title, DocumentInputType inputType,
                                     String originalFileName, String mediaType, String rawContent,
                                     String contentHash, ManagedDocumentStatus status, long version,
                                     String indexFailureReason, String archiveReason,
                                     Instant createdAt, Instant updatedAt,
                                     Instant publishedAt, Instant archivedAt) {
    /** 从未删除的领域聚合创建详情视图。 */
    public static ManagedDocumentDetails from(ManagedDocument value,
                                              KnowledgeSpaceSummary space) {
        return new ManagedDocumentDetails(value.id(), value.spaceId(),
                space,
                value.title(), value.inputType(),
                value.originalFileName(), value.mediaType(), value.rawContent(), value.contentHash(),
                value.status(), value.version(), value.indexFailureReason(), value.archiveReason(),
                value.createdAt(), value.updatedAt(), value.publishedAt(), value.archivedAt());
    }

    /** 兼容内部任务的 GLOBAL 视图；外部查询必须使用显式空间摘要重载。 */
    public static ManagedDocumentDetails from(ManagedDocument value) {
        return from(value, KnowledgeSpaceSummary.global(value.spaceId()));
    }
}
