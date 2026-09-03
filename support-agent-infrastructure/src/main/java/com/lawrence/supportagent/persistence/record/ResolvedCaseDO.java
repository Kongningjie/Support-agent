package com.lawrence.supportagent.persistence.record;

import java.time.Instant;

/** MyBatis 使用的已解决案例数据记录，与领域聚合保持隔离。 */
public class ResolvedCaseDO {
    /** 案例内部自增主键。 */
    public Long id;
    /** 来源已解决工单的内部主键。 */
    public long sourceTicketId;
    /** 案例标题。 */
    public String title;
    /** 问题现象与背景。 */
    public String problem;
    /** 人工确认的根因。 */
    public String cause;
    /** 经验证的解决步骤。 */
    public String solution;
    /** 案例状态枚举名称。 */
    public String status;
    /** 规范化案例内容的 SHA-256。 */
    public String contentHash;
    /** 乐观锁及知识版本号。 */
    public long version;
    /** 发布最终失败的脱敏摘要。 */
    public String publishFailureReason;
    /** 人工拒绝案例的原因。 */
    public String rejectionReason;
    /** 案例退出检索的人工原因。 */
    public String archiveReason;
    /** 草稿软删除标识。 */
    public boolean deleted;
    /** 删除操作者。 */
    public String deletedBy;
    /** 删除 UTC 时间。 */
    public Instant deletedAt;
    /** 创建操作者或系统身份。 */
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
