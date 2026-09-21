package com.lawrence.supportagent.auth;

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

/** 使用 Redis 原子维护数量受控且只保存 SHA-256 的不透明 Bearer Token。 */
public class RedisAccessTokenAdapter implements AccessTokenPort {
    private static final String PREFIX = "support-agent:auth:";
    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();

    /** 注入字符串 Redis 客户端。 */
    public RedisAccessTokenAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** {@inheritDoc} */
    @Override public IssuedToken issue(AuthenticatedUser user, Instant issuedAt, Duration ttl,
                                       int maximumActiveTokens) {
        if (maximumActiveTokens <= 0) throw new IllegalArgumentException("并发 Token 上限必须大于 0");
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String raw = user.userId() + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String digest = sha256(raw);
        Instant expiresAt = issuedAt.plus(ttl);
        String script = "local indexType=redis.call('TYPE',KEYS[2]).ok; "
                + "if indexType=='set' then local legacy=redis.call('SMEMBERS',KEYS[2]); "
                + "redis.call('DEL',KEYS[2]); for _,oldDigest in ipairs(legacy) do "
                + "redis.call('ZADD',KEYS[2],0,oldDigest) end end; "
                + "redis.call('HSET',KEYS[1],'userId',ARGV[1],'username',ARGV[2],"
                + "'role',ARGV[3],'mustChangePassword',ARGV[4],'issuedAt',ARGV[5],'expiresAt',ARGV[6]); "
                + "redis.call('EXPIRE',KEYS[1],ARGV[7]); redis.call('ZADD',KEYS[2],ARGV[8],ARGV[9]); "
                + "redis.call('EXPIRE',KEYS[2],ARGV[7]); local count=redis.call('ZCARD',KEYS[2]); "
                + "while count>tonumber(ARGV[10]) do local oldest=redis.call('ZRANGE',KEYS[2],0,0)[1]; "
                + "if not oldest then break end; redis.call('ZREM',KEYS[2],oldest); "
                + "redis.call('DEL',ARGV[11]..oldest); count=count-1 end; return 1";
        redis.execute(new DefaultRedisScript<>(script, Long.class),
                List.of(tokenKey(user.userId(), digest), indexKey(user.userId())),
                user.userId().toString(), user.username(), user.role().name(),
                Boolean.toString(user.mustChangePassword()), issuedAt.toString(), expiresAt.toString(),
                Long.toString(ttl.toSeconds()), Long.toString(issuedAt.toEpochMilli()), digest,
                Integer.toString(maximumActiveTokens), PREFIX + "token:" + user.userId() + ":");
        return new IssuedToken(raw, expiresAt);
    }

    /** {@inheritDoc} */
    @Override public Optional<AuthenticatedUser> authenticate(String rawToken) {
        ParsedToken parsed = parse(rawToken);
        if (parsed == null) return Optional.empty();
        Map<Object, Object> values = redis.opsForHash().entries(tokenKey(parsed.userId(), sha256(rawToken)));
        if (values.isEmpty()) return Optional.empty();
        try {
            UUID stored = UUID.fromString(values.get("userId").toString());
            if (!stored.equals(parsed.userId())) return Optional.empty();
            return Optional.of(new AuthenticatedUser(stored, values.get("username").toString(),
                    UserRole.valueOf(values.get("role").toString()),
                    Boolean.parseBoolean(values.getOrDefault("mustChangePassword", "false").toString())));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /** {@inheritDoc} */
    @Override public void revoke(String rawToken) {
        ParsedToken parsed = parse(rawToken);
        if (parsed == null) return;
        String digest = sha256(rawToken);
        String script = "redis.call('DEL',KEYS[1]); local indexType=redis.call('TYPE',KEYS[2]).ok; "
                + "if indexType=='set' then redis.call('SREM',KEYS[2],ARGV[1]) "
                + "elseif indexType=='zset' then redis.call('ZREM',KEYS[2],ARGV[1]) end; return 1";
        redis.execute(new DefaultRedisScript<>(script, Long.class),
                List.of(tokenKey(parsed.userId(), digest), indexKey(parsed.userId())), digest);
    }

    /** {@inheritDoc} */
    @Override public void revokeAll(UUID userId) {
        String script = "local indexType=redis.call('TYPE',KEYS[1]).ok; local members={}; "
                + "if indexType=='set' then members=redis.call('SMEMBERS',KEYS[1]) "
                + "elseif indexType=='zset' then members=redis.call('ZRANGE',KEYS[1],0,-1) end; "
                + "for _,digest in ipairs(members) do redis.call('DEL',ARGV[1]..digest) end; "
                + "redis.call('DEL',KEYS[1]); return #members";
        redis.execute(new DefaultRedisScript<>(script, Long.class), List.of(indexKey(userId)),
                PREFIX + "token:" + userId + ":");
    }

    /** 从原始 Token 的公开前缀解析用户 UUID。 */
    private ParsedToken parse(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return null;
        int separator = rawToken.indexOf('.');
        if (separator <= 0 || separator == rawToken.length() - 1) return null;
        try {
            return new ParsedToken(UUID.fromString(rawToken.substring(0, separator)));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** 计算原始 Token 的小写 SHA-256。 */
    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 不支持 SHA-256", exception);
        }
    }

    /** 返回单个哈希 Token 的 Redis 键。 */
    private String tokenKey(UUID userId, String digest) {
        return PREFIX + "token:" + userId + ":" + digest;
    }

    /** 返回用户 Token 哈希有序索引键。 */
    private String indexKey(UUID userId) {
        return PREFIX + "user-tokens:" + userId;
    }

    /** @param userId 原始 Token 前缀中的公开用户 UUID */
    private record ParsedToken(UUID userId) { }
}
