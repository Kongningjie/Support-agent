package com.lawrence.supportagent.auth;

import com.lawrence.supportagent.user.UserAccount;
import com.lawrence.supportagent.user.UserRole;
import com.lawrence.supportagent.user.UserStatus;
import java.time.Instant;
import java.util.UUID;

/** 不包含密码哈希和 Token 的用户公开视图。 */
public record UserView(UUID userId, String username, String displayName, UserRole role,
                       UserStatus status, long version, boolean mustChangePassword,
                       Instant lockedUntil, Instant createdAt, Instant updatedAt) {
    /** 从领域用户创建安全公开视图。 */
    public static UserView from(UserAccount account) {
        return new UserView(account.userId(), account.username(), account.displayName(),
                account.role(), account.status(), account.version(),
                account.mustChangePassword(), account.lockedUntil(), account.createdAt(),
                account.updatedAt());
    }
}
