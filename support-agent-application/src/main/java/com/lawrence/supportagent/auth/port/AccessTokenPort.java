package com.lawrence.supportagent.auth.port;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 隔离不透明 Token 的随机生成、哈希存储、认证与撤销。 */
public interface AccessTokenPort {
    /** 签发原始 Token；服务端存储只能保留哈希。 */
    IssuedToken issue(AuthenticatedUser user, Instant issuedAt, Duration ttl);
    /** 验证原始 Token 并返回其不可变用户快照。 */
    Optional<AuthenticatedUser> authenticate(String rawToken);
    /** 幂等撤销当前原始 Token。 */
    void revoke(String rawToken);
    /** 撤销指定用户的全部有效 Token。 */
    void revokeAll(UUID userId);

    /** @param rawToken 仅返回给客户端一次的原始 Token @param expiresAt 绝对失效时间 */
    record IssuedToken(String rawToken, Instant expiresAt) {
        /** 拒绝空 Token 或缺失失效时间。 */
        public IssuedToken {
            if (rawToken == null || rawToken.isBlank() || expiresAt == null) {
                throw new IllegalArgumentException("签发 Token 字段不能为空");
            }
        }
    }
}
