package com.lawrence.supportagent.auth;

import com.lawrence.supportagent.user.UserRole;
import java.util.UUID;

/** 当前请求已经认证且不可变的最小用户上下文。 */
public record AuthenticatedUser(UUID userId, String username, UserRole role) {
    /** 拒绝缺失标识、用户名或角色的认证上下文。 */
    public AuthenticatedUser {
        if (userId == null || username == null || username.isBlank() || role == null) {
            throw new IllegalArgumentException("认证用户字段不能为空");
        }
    }

    /** 返回管理员是否拥有跨用户工单访问权限。 */
    public boolean administrator() {
        return role == UserRole.ADMIN;
    }
}
