package com.lawrence.supportagent.persistence.record;

import java.time.Instant;

/** MyBatis 使用的知识空间数据记录。 */
public class KnowledgeSpaceDO {
    /** MySQL 内部自增主键。 */
    public Long id;
    /** 空间公开 UUID 的 16 字节表示。 */
    public byte[] spaceId;
    /** 企业内稳定且唯一的空间代码。 */
    public String code;
    /** 用户可见空间名称。 */
    public String name;
    /** 空间用途与知识边界说明。 */
    public String description;
    /** ENTERPRISE 或 RESTRICTED。 */
    public String visibility;
    /** ACTIVE 或 DISABLED。 */
    public String status;
    /** 是否为内置系统空间。 */
    public boolean systemSpace;
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
}
