package com.lawrence.supportagent.persistence.record;

import java.time.Instant;

/** MyBatis 使用的本地用户数据记录，与领域用户保持隔离。 */
public class UserAccountDO {
    /** MySQL 内部自增主键。 */
    public Long id;
    /** 公开用户 UUID 的 16 字节表示。 */
    public byte[] userId;
    /** 规范化登录用户名。 */
    public String username;
    /** 用户展示名称。 */
    public String displayName;
    /** BCrypt 密码哈希。 */
    public String passwordHash;
    /** USER 或 ADMIN。 */
    public String role;
    /** ACTIVE 或 DISABLED。 */
    public String status;
    /** 乐观锁版本。 */
    public long version;
    /** 最近设置密码时间。 */
    public Instant passwordChangedAt;
    /** 是否必须先修改管理员设置的一次性密码。 */
    public boolean mustChangePassword;
    /** 临时锁定截止时间；空值表示未锁定。 */
    public Instant lockedUntil;
    /** 创建操作者。 */
    public String createdBy;
    /** 创建时间。 */
    public Instant createdAt;
    /** 最近修改操作者。 */
    public String updatedBy;
    /** 最近修改时间。 */
    public Instant updatedAt;
}
