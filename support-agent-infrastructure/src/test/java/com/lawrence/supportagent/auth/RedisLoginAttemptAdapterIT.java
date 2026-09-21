package com.lawrence.supportagent.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 使用真实 Redis 验证用户名和客户端来源两条独立登录限流边界。 */
@Testcontainers
class RedisLoginAttemptAdapterIT {
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.7"))
            .withExposedPorts(6379);
    private LettuceConnectionFactory connectionFactory;
    private RedisLoginAttemptAdapter adapter;

    /** 为每个测试建立空 Redis 限流适配器。 */
    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        adapter = new RedisLoginAttemptAdapter(redis);
    }

    /** 关闭连接以避免容器停止后后台重连。 */
    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    /** 第三、第四和第五次失败分别应用 30 秒、2 分钟和 15 分钟退避。 */
    @Test
    void shouldApplyProgressiveBackoff() {
        Instant now = Instant.parse("2026-09-21T00:00:00Z");
        assertThat(adapter.recordFailure("alice", "source-a", now).blockedUntil()).isNull();
        assertThat(adapter.recordFailure("alice", "source-a", now).blockedUntil()).isNull();
        assertThat(adapter.recordFailure("alice", "source-a", now).blockedUntil())
                .isEqualTo(now.plusSeconds(30));
        assertThat(adapter.recordFailure("alice", "source-a", now.plusSeconds(31)).blockedUntil())
                .isEqualTo(now.plusSeconds(151));
        assertThat(adapter.recordFailure("alice", "source-a", now.plusSeconds(152)).blockedUntil())
                .isEqualTo(now.plusSeconds(1052));
    }

    /** 成功登录后应清理当前用户名和来源的失败计数。 */
    @Test
    void shouldClearCurrentSubjectsAfterSuccess() {
        Instant now = Instant.parse("2026-09-21T00:00:00Z");
        adapter.recordFailure("alice", "source-a", now);
        adapter.clear("alice", "source-a");
        assertThat(adapter.blockedUntil("alice", "source-a", now)).isEmpty();
    }
}
