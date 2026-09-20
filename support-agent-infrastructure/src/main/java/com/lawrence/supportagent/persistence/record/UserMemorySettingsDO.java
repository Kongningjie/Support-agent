package com.lawrence.supportagent.persistence.record;

import java.time.Instant;

/** MyBatis 使用的用户长期记忆设置数据记录。 */
public class UserMemorySettingsDO {
    public Long id;
    public byte[] userId;
    public boolean enabled;
    public long version;
    public Instant createdAt;
    public Instant updatedAt;
}
