package com.lawrence.supportagent.user;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** 本地用户聚合，集中维护登录标识、角色、状态和审计版本。 */
public record UserAccount(Long id, UUID userId, String username, String displayName,
                          String passwordHash, UserRole role, UserStatus status, long version,
                          Instant passwordChangedAt, String createdBy, Instant createdAt,
                          String updatedBy, Instant updatedAt) {
    private static final Pattern USERNAME = Pattern.compile("[a-z][a-z0-9._-]{2,63}");

    /** 校验持久化和新建用户都必须满足的领域不变量。 */
    public UserAccount {
        if (userId == null || passwordChangedAt == null || createdAt == null || updatedAt == null
                || role == null || status == null || version < 0) {
            throw new IllegalArgumentException("用户标识、状态、角色、版本和时间不能为空");
        }
        username = normalizeUsername(username);
        displayName = required(displayName, "展示名称", 100);
        passwordHash = required(passwordHash, "密码哈希", 200);
        createdBy = required(createdBy, "创建人", 64);
        updatedBy = required(updatedBy, "更新人", 64);
    }

    /** 创建尚未取得 MySQL 内部主键的活动用户。 */
    public static UserAccount create(UUID userId, String username, String displayName,
                                     String passwordHash, UserRole role,
                                     String operator, Instant now) {
        return new UserAccount(null, userId, username, displayName, passwordHash, role,
                UserStatus.ACTIVE, 0, now, operator, now, operator, now);
    }

    /** 由管理员变更账号状态并递增乐观锁版本。 */
    public UserAccount changeStatus(UserStatus newStatus, String operator, Instant now) {
        if (newStatus == null) {
            throw new IllegalArgumentException("用户状态不能为空");
        }
        return new UserAccount(id, userId, username, displayName, passwordHash, role, newStatus,
                version + 1, passwordChangedAt, createdBy, createdAt, operator, now);
    }

    /** 规范化并校验公开登录名。 */
    public static String normalizeUsername(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("用户名必须以字母开头且只能包含字母、数字、点、下划线或短横线，长度为 3 至 64");
        }
        return normalized;
    }

    /** 校验必填文本的空值和最大长度。 */
    private static String required(String value, String field, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        String normalized = value.strip();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + "不能超过 " + maximumLength + " 个字符");
        }
        return normalized;
    }
}
