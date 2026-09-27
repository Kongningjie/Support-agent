package com.lawrence.supportagent.persistence.record;

import java.time.Instant;

/** MyBatis 使用的空间成员关系数据记录。 */
public class SpaceMembershipDO {
    /** MySQL 内部自增主键。 */
    public Long id;
    /** 成员公开用户 UUID 的 16 字节表示。 */
    public byte[] userId;
    /** 空间公开 UUID 的 16 字节表示。 */
    public byte[] spaceId;
    /** READER、EDITOR 或 MANAGER。 */
    public String role;
    /** ACTIVE 或 REVOKED。 */
    public String status;
    /** 乐观锁版本。 */
    public long version;
    /** 创建操作者。 */
    public String createdBy;
    /** 创建时间。 */
    public Instant createdAt;
    /** 最近修改操作者。 */
    public String updatedBy;
    /** 最近修改时间。 */
    public Instant updatedAt;
    /** 最近撤销操作者。 */
    public String revokedBy;
    /** 最近撤销时间。 */
    public Instant revokedAt;
}
