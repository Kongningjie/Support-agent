package com.lawrence.supportagent.persistence.record;

import java.time.Instant;

/** MyBatis 使用的长期记忆持久化数据记录。 */
public class UserMemoryDO {
    public Long id;
    public byte[] memoryId;
    public byte[] userId;
    public String memoryType;
    public String content;
    public String contentHash;
    public String status;
    public boolean pinned;
    public byte[] sourceConversationId;
    public byte[] sourceTurnId;
    public Instant expiresAt;
    public long version;
    public String createdBy;
    public Instant createdAt;
    public String confirmedBy;
    public Instant confirmedAt;
    public String updatedBy;
    public Instant updatedAt;
    public String revokedBy;
    public Instant revokedAt;
}
