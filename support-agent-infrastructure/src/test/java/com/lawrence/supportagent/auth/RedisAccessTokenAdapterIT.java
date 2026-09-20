package com.lawrence.supportagent.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.auth.port.AccessTokenPort.IssuedToken;
import com.lawrence.supportagent.user.UserRole;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.awaitility.Awaitility;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 使用真实 Redis 验证不透明 Token 哈希存储、认证和原子撤销。 */
@Testcontainers
class RedisAccessTokenAdapterIT {
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.7"))
            .withExposedPorts(6379);
    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private RedisAccessTokenAdapter adapter;

    /** 为每个测试建立空 Redis 和 Token 适配器。 */
    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        adapter = new RedisAccessTokenAdapter(redis);
    }

    /** 关闭连接以避免容器停止后后台重连。 */
    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    /** Token 应可认证且 Redis 键和值均不得出现原始 Token。 */
    @Test
    void shouldStoreOnlyTokenDigest() {
        AuthenticatedUser user = user();
        IssuedToken issued = adapter.issue(user, Instant.EPOCH, Duration.ofHours(2));

        assertThat(adapter.authenticate(issued.rawToken())).contains(user);
        Set<String> keys = redis.keys("support-agent:auth:*");
        assertThat(keys).isNotNull().allMatch(key -> !key.contains(issued.rawToken()));
        assertThat(keys.stream().flatMap(key -> redis.type(key).code().equals("hash")
                        ? redis.opsForHash().entries(key).values().stream().map(Object::toString)
                        : redis.opsForSet().members(key).stream()))
                .noneMatch(issued.rawToken()::equals);
        assertThat(redis.getExpire(keys.stream().filter(key -> key.contains(":token:")).findFirst().orElseThrow()))
                .isBetween(7_190L, 7_200L);
    }

    /** 单 Token 和用户全部 Token 撤销均应立即使认证失效。 */
    @Test
    void shouldRevokeOneOrAllTokens() {
        AuthenticatedUser user = user();
        IssuedToken first = adapter.issue(user, Instant.EPOCH, Duration.ofHours(2));
        IssuedToken second = adapter.issue(user, Instant.EPOCH, Duration.ofHours(2));

        adapter.revoke(first.rawToken());
        assertThat(adapter.authenticate(first.rawToken())).isEmpty();
        assertThat(adapter.authenticate(second.rawToken())).contains(user);

        adapter.revokeAll(user.userId());
        assertThat(adapter.authenticate(second.rawToken())).isEmpty();
        assertThat(redis.keys("support-agent:auth:token:" + user.userId() + ":*")).isEmpty();
    }

    /** Redis TTL 到期后原始 Token 必须自动失效且不能滑动续期。 */
    @Test
    void shouldExpireTokenAtFixedDeadline() {
        IssuedToken issued = adapter.issue(user(), Instant.now(), Duration.ofSeconds(1));
        assertThat(adapter.authenticate(issued.rawToken())).isPresent();

        Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(adapter.authenticate(issued.rawToken())).isEmpty());
    }

    /** 创建稳定的普通认证用户。 */
    private AuthenticatedUser user() {
        return new AuthenticatedUser(UUID.fromString("20000000-0000-0000-0000-000000000001"),
                "alice", UserRole.USER);
    }
}
