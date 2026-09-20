package com.lawrence.supportagent.memory;

import java.time.Instant;
import java.util.UUID;

/** 用户长期记忆聚合，集中维护确认、更正、固定、撤销和乐观锁规则。 */
public record UserMemory(Long id, UUID memoryId, UUID userId, MemoryType memoryType,
                         String content, String contentHash, MemoryStatus status,
                         boolean pinned, UUID sourceConversationId, UUID sourceTurnId,
                         Instant expiresAt, long version, String createdBy, Instant createdAt,
                         String confirmedBy, Instant confirmedAt, String updatedBy,
                         Instant updatedAt, String revokedBy, Instant revokedAt) {
    /** 校验持久化对象及新建候选都必须满足的领域不变量。 */
    public UserMemory {
        if (memoryId == null || userId == null || memoryType == null || status == null
                || sourceConversationId == null || sourceTurnId == null || version < 0
                || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("长期记忆标识、类型、状态、来源、版本和时间不能为空");
        }
        content = required(content, "记忆正文", 500);
        contentHash = required(contentHash, "正文哈希", 64);
        createdBy = required(createdBy, "创建人", 64);
        updatedBy = required(updatedBy, "更新人", 64);
        if (status == MemoryStatus.ACTIVE && (confirmedBy == null || confirmedAt == null)) {
            throw new IllegalArgumentException("有效记忆必须记录确认人和确认时间");
        }
        if (status == MemoryStatus.REVOKED && (revokedBy == null || revokedAt == null)) {
            throw new IllegalArgumentException("已撤销记忆必须记录撤销人和撤销时间");
        }
    }

    /** 创建只能由内部模型候选流程写入的未确认记忆。 */
    public static UserMemory propose(UUID memoryId, UUID userId, MemoryType memoryType,
                                     String content, String contentHash,
                                     UUID sourceConversationId, UUID sourceTurnId,
                                     Instant now) {
        return new UserMemory(null, memoryId, userId, memoryType, content, contentHash,
                MemoryStatus.PROPOSED, false, sourceConversationId, sourceTurnId,
                null, 0, "MODEL_CANDIDATE", now, null, null,
                "MODEL_CANDIDATE", now, null, null);
    }

    /** 由所有者确认候选并使其获得上下文注入资格。 */
    public UserMemory confirm(String operator, Instant now) {
        if (status != MemoryStatus.PROPOSED) {
            throw new IllegalStateException("只有候选记忆可以确认");
        }
        return copy(content, contentHash, MemoryStatus.ACTIVE, pinned, expiresAt,
                version + 1, operator, now, operator, now, null, null);
    }

    /** 更正用户可见正文、失效时间或固定标志并递增版本。 */
    public UserMemory revise(String newContent, String newHash, Instant newExpiresAt,
                             boolean newPinned, String operator, Instant now) {
        if (status == MemoryStatus.REVOKED) {
            throw new IllegalStateException("已撤销记忆不能更正");
        }
        return copy(newContent, newHash, status, newPinned, newExpiresAt,
                version + 1, operator, now, confirmedBy, confirmedAt, revokedBy, revokedAt);
    }

    /** 撤销候选或有效记忆，使其不再具有上下文注入资格。 */
    public UserMemory revoke(String operator, Instant now) {
        if (status == MemoryStatus.REVOKED) {
            throw new IllegalStateException("记忆已经撤销");
        }
        return copy(content, contentHash, MemoryStatus.REVOKED, pinned, expiresAt,
                version + 1, operator, now, confirmedBy, confirmedAt, operator, now);
    }

    /** 生成保留不可变标识及创建审计字段的新版本聚合。 */
    private UserMemory copy(String newContent, String newHash, MemoryStatus newStatus,
                            boolean newPinned, Instant newExpiresAt, long newVersion,
                            String operator, Instant now, String newConfirmedBy,
                            Instant newConfirmedAt, String newRevokedBy, Instant newRevokedAt) {
        return new UserMemory(id, memoryId, userId, memoryType, newContent, newHash,
                newStatus, newPinned, sourceConversationId, sourceTurnId, newExpiresAt,
                newVersion, createdBy, createdAt, newConfirmedBy, newConfirmedAt,
                operator, now, newRevokedBy, newRevokedAt);
    }

    /** 校验必填文本的空值与 Unicode 码点长度。 */
    private static String required(String value, String field, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        String normalized = value.strip();
        if (normalized.codePointCount(0, normalized.length()) > maximumLength) {
            throw new IllegalArgumentException(field + "不能超过 " + maximumLength + " 个字符");
        }
        return normalized;
    }
}
