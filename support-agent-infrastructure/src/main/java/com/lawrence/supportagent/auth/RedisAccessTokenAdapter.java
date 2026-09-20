package com.lawrence.supportagent.auth;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.auth.port.AccessTokenPort;
import com.lawrence.supportagent.user.UserRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** 使用 Redis 原子维护只保存 SHA-256 的不透明 Bearer Token。 */
public class RedisAccessTokenAdapter implements AccessTokenPort {
    private static final String PREFIX = "support-agent:auth:";
    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();

    /** 注入字符串 Redis 客户端。 */
    public RedisAccessTokenAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** {@inheritDoc} */
    @Override public IssuedToken issue(AuthenticatedUser user, Instant issuedAt, Duration ttl) {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String raw = user.userId() + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String digest = sha256(raw);
        String tokenKey = tokenKey(user.userId(), digest);
        String indexKey = indexKey(user.userId());
        Instant expiresAt = issuedAt.plus(ttl);
        String script = "redis.call('HSET',KEYS[1],'userId',ARGV[1],'username',ARGV[2]," 
                + "'role',ARGV[3],'issuedAt',ARGV[4],'expiresAt',ARGV[5]); "
                + "redis.call('EXPIRE',KEYS[1],ARGV[6]); redis.call('SADD',KEYS[2],ARGV[7]); "
                + "redis.call('EXPIRE',KEYS[2],ARGV[6]); return 1";
        redis.execute(new DefaultRedisScript<>(script, Long.class), List.of(tokenKey, indexKey),
                user.userId().toString(), user.username(), user.role().name(),
                issuedAt.toString(), expiresAt.toString(), Long.toString(ttl.toSeconds()), digest);
        return new IssuedToken(raw, expiresAt);
    }

    /** {@inheritDoc} */
    @Override public Optional<AuthenticatedUser> authenticate(String rawToken) {
        ParsedToken parsed = parse(rawToken);
        if (parsed == null) {
            return Optional.empty();
        }
        Map<Object, Object> values = redis.opsForHash().entries(tokenKey(parsed.userId(), sha256(rawToken)));
        if (values.isEmpty()) {
            return Optional.empty();
        }
        try {
            UUID stored = UUID.fromString(values.get("userId").toString());
            if (!stored.equals(parsed.userId())) {
                return Optional.empty();
            }
            return Optional.of(new AuthenticatedUser(stored, values.get("username").toString(),
                    UserRole.valueOf(values.get("role").toString())));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /** {@inheritDoc} */
    @Override public void revoke(String rawToken) {
        ParsedToken parsed = parse(rawToken);
        if (parsed == null) {
            return;
        }
        String digest = sha256(rawToken);
        String script = "redis.call('DEL',KEYS[1]); redis.call('SREM',KEYS[2],ARGV[1]); return 1";
        redis.execute(new DefaultRedisScript<>(script, Long.class),
                List.of(tokenKey(parsed.userId(), digest), indexKey(parsed.userId())), digest);
    }

    /** {@inheritDoc} */
    @Override public void revokeAll(UUID userId) {
        String script = "local members=redis.call('SMEMBERS',KEYS[1]); "
                + "for _,digest in ipairs(members) do redis.call('DEL',ARGV[1]..digest) end; "
                + "redis.call('DEL',KEYS[1]); return #members";
        redis.execute(new DefaultRedisScript<>(script, Long.class), List.of(indexKey(userId)),
                PREFIX + "token:" + userId + ":");
    }

    /** 从原始 Token 的公开前缀解析用户 UUID。 */
    private ParsedToken parse(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        int separator = rawToken.indexOf('.');
        if (separator <= 0 || separator == rawToken.length() - 1) {
            return null;
        }
        try {
            return new ParsedToken(UUID.fromString(rawToken.substring(0, separator)));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** 计算原始 Token 的小写 SHA-256。 */
    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 不支持 SHA-256", exception);
        }
    }

    /** 返回单个哈希 Token 的 Redis 键。 */
    private String tokenKey(UUID userId, String digest) {
        return PREFIX + "token:" + userId + ":" + digest;
    }

    /** 返回用户 Token 哈希集合 Redis 键。 */
    private String indexKey(UUID userId) {
        return PREFIX + "user-tokens:" + userId;
    }

    /** @param userId 原始 Token 前缀中的公开用户 UUID */
    private record ParsedToken(UUID userId) { }
}
