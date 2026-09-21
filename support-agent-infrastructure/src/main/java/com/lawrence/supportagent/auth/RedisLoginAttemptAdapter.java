package com.lawrence.supportagent.auth;

import com.lawrence.supportagent.auth.port.LoginAttemptPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** 使用不可逆主体哈希在 Redis 原子维护三级登录退避。 */
public class RedisLoginAttemptAdapter implements LoginAttemptPort {
    private static final String PREFIX = "support-agent:auth:login-limit:";
    private static final long STATE_TTL_SECONDS = 86_400;
    private final StringRedisTemplate redis;

    /** 注入字符串 Redis 客户端。 */
    public RedisLoginAttemptAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** {@inheritDoc} */
    @Override public Optional<Instant> blockedUntil(String username, String clientSource,
                                                    Instant now) {
        Instant usernameUntil = readBlockedUntil(key("username", username));
        Instant sourceUntil = readBlockedUntil(key("source", clientSource));
        Instant latest = latest(usernameUntil, sourceUntil);
        return latest != null && latest.isAfter(now) ? Optional.of(latest) : Optional.empty();
    }

    /** {@inheritDoc} */
    @Override public LoginAttemptDecision recordFailure(String username, String clientSource,
                                                        Instant now) {
        String script = "local maxCount=0; local maxUntil=0; "
                + "for _,key in ipairs(KEYS) do "
                + "local count=redis.call('HINCRBY',key,'count',1); local delay=0; "
                + "if count==3 then delay=30 elseif count==4 then delay=120 elseif count>=5 then delay=900 end; "
                + "local blockedUntil=0; if delay>0 then blockedUntil=tonumber(ARGV[1])+delay; "
                + "redis.call('HSET',key,'blockedUntil',blockedUntil) end; "
                + "redis.call('EXPIRE',key,ARGV[2]); if count>maxCount then maxCount=count end; "
                + "if blockedUntil>maxUntil then maxUntil=blockedUntil end end; "
                + "return tostring(maxCount)..':'..tostring(maxUntil)";
        String value = redis.execute(new DefaultRedisScript<>(script, String.class),
                keys(username, clientSource), Long.toString(now.getEpochSecond()),
                Long.toString(STATE_TTL_SECONDS));
        if (value == null) throw new IllegalStateException("登录失败状态写入失败");
        String[] fields = value.split(":", -1);
        long count = Long.parseLong(fields[0]);
        long until = Long.parseLong(fields[1]);
        return new LoginAttemptDecision(count, until == 0 ? null : Instant.ofEpochSecond(until));
    }

    /** {@inheritDoc} */
    @Override public void clear(String username, String clientSource) {
        redis.delete(keys(username, clientSource));
    }

    /** {@inheritDoc} */
    @Override public void clearUsername(String username) {
        redis.delete(key("username", username));
    }

    /** 读取单个主体的锁定截止时间；异常值按长期锁定处理。 */
    private Instant readBlockedUntil(String key) {
        Object value = redis.opsForHash().get(key, "blockedUntil");
        if (value == null) return null;
        try {
            return Instant.ofEpochSecond(Long.parseLong(value.toString()));
        } catch (RuntimeException exception) {
            return Instant.MAX;
        }
    }

    /** 返回两个可空时间中的较晚值。 */
    private Instant latest(Instant first, Instant second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.isAfter(second) ? first : second;
    }

    /** 分别生成用户名和来源的不可逆 Redis 键。 */
    private List<String> keys(String username, String clientSource) {
        return List.of(key("username", username), key("source", clientSource));
    }

    /** 使用主体类型和值的 SHA-256 构造不泄露原值的 Redis 键。 */
    private String key(String subjectType, String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((subjectType + '\u0000' + value).getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 不支持 SHA-256", exception);
        }
    }
}
