package com.lawrence.supportagent.persistence.record;

import java.time.Instant;

/** MyBatis 使用的托管文档数据记录，与领域聚合保持隔离。 */
public class ManagedDocumentDO {
    /** 文档内部自增主键。 */
    public Long id;
    /** 知识文档标题。 */
    public String title;
    /** 文档输入方式枚举名称。 */
    public String inputType;
    /** 上传时的原文件名。 */
    public String originalFileName;
    /** 服务端确认的媒体类型。 */
    public String mediaType;
    /** UTF-8 解码后的完整原文。 */
    public String rawContent;
    /** 规范化原文的 SHA-256。 */
    public String contentHash;
    /** 文档状态枚举名称。 */
    public String status;
    /** 乐观锁及知识版本号。 */
    public long version;
    /** 索引最终失败的脱敏摘要。 */
    public String indexFailureReason;
    /** 文档退出检索的人工原因。 */
    public String archiveReason;
    /** 草稿软删除标识。 */
    public boolean deleted;
    /** 删除操作者。 */
    public String deletedBy;
    /** 删除 UTC 时间。 */
    public Instant deletedAt;
    /** 创建操作者。 */
    public String createdBy;
    /** 创建 UTC 时间。 */
    public Instant createdAt;
    /** 最近修改操作者。 */
    public String updatedBy;
    /** 最近修改 UTC 时间。 */
    public Instant updatedAt;
    /** 发布操作者。 */
    public String publishedBy;
    /** 发布 UTC 时间。 */
    public Instant publishedAt;
    /** 归档操作者。 */
    public String archivedBy;
    /** 归档 UTC 时间。 */
    public Instant archivedAt;
}
