package com.lawrence.supportagent.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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

    /** 同一用户名更换来源或同一来源更换用户名都不能绕过失败阈值。 */
    @Test
    void shouldLimitByUsernameOrClientSource() {
        for (int attempt = 0; attempt < 5; attempt++) {
            adapter.recordFailure("alice", "source-a", 5, Duration.ofMinutes(15));
        }

        assertThat(adapter.blocked("alice", "source-b", 5)).isTrue();
        assertThat(adapter.blocked("another-user", "source-a", 5)).isTrue();
        assertThat(adapter.blocked("another-user", "source-b", 5)).isFalse();
    }

    /** 成功登录后应清理当前用户名和来源的失败计数。 */
    @Test
    void shouldClearCurrentSubjectsAfterSuccess() {
        adapter.recordFailure("alice", "source-a", 1, Duration.ofMinutes(15));
        adapter.clear("alice", "source-a");
        assertThat(adapter.blocked("alice", "source-a", 1)).isFalse();
    }
}
