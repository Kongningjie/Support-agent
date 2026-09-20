package com.lawrence.supportagent.auth;

import com.lawrence.supportagent.auth.port.LoginAttemptPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** 使用不可逆主体哈希在 Redis 维护短时登录失败计数。 */
public class RedisLoginAttemptAdapter implements LoginAttemptPort {
    private static final String PREFIX = "support-agent:auth:login-limit:";
    private final StringRedisTemplate redis;

    /** 注入字符串 Redis 客户端。 */
    public RedisLoginAttemptAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** {@inheritDoc} */
    @Override public boolean blocked(String username, String clientSource, int maximumFailures) {
        List<String> values = redis.opsForValue().multiGet(keys(username, clientSource));
        return values != null && values.stream().anyMatch(value -> blocked(value, maximumFailures));
    }

    /** {@inheritDoc} */
    @Override public void recordFailure(String username, String clientSource,
                                        int maximumFailures, Duration window) {
        String script = "for _,key in ipairs(KEYS) do local count=redis.call('INCR',key); "
                + "if count==1 then redis.call('EXPIRE',key,ARGV[1]) end; "
                + "if count>tonumber(ARGV[2]) then redis.call('SET',key,ARGV[2],'EX',ARGV[1]) end end; return 1";
        redis.execute(new DefaultRedisScript<>(script, Long.class),
                keys(username, clientSource), Long.toString(window.toSeconds()),
                Integer.toString(maximumFailures));
    }

    /** {@inheritDoc} */
    @Override public void clear(String username, String clientSource) {
        redis.delete(keys(username, clientSource));
    }

    /** 判断单个 Redis 失败计数是否达到阈值，异常数据按已阻断处理。 */
    private boolean blocked(String value, int maximumFailures) {
        if (value == null) return false;
        try {
            return Long.parseLong(value) >= maximumFailures;
        } catch (NumberFormatException exception) {
            return true;
        }
    }

    /** 分别生成用户名和来源的不可逆 Redis 键，任一主体达到阈值即限流。 */
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
