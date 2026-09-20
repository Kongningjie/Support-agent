package com.lawrence.supportagent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lawrence.supportagent.chat.port.ConversationStorePort.CompletedTurn;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/** 使用真实 Redis 验证会话版本、幂等重放、运行围栏和建议消费原子性。 */
@Testcontainers
class RedisConversationStoreAdapterIT {
    private static final UUID OWNER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.7"))
            .withExposedPorts(6379);
    private RedisConversationStoreAdapter store;
    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;

    /** 为每个测试创建连接并清空独立容器数据。 */
    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        store = new RedisConversationStoreAdapter(redisTemplate,
                JsonMapper.builder().findAndAddModules().build());
    }

    /** 关闭当前测试创建的 Redis 连接，避免容器停止后后台重连。 */
    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    /** 成功轮次应递增版本，并使用新序号重放相同完成内容。 */
    @Test
    void shouldCommitAndReplayCompletedMessage() {
        UUID messageId = UUID.randomUUID(); UUID runId = UUID.randomUUID(); Instant now = Instant.now();
        var begin = store.begin(OWNER_ID, null, messageId, "问题", null, runId, now);
        UUID suggestionId = UUID.randomUUID();
        CompletedTurn turn = new CompletedTurn(UUID.randomUUID(), messageId, runId, "问题", "问题",
                ChatIntent.SUPPORT_QUERY, "无可靠知识", null, List.of(), suggestionId,
                "冻结上下文", "NO_RELIABLE_KNOWLEDGE", now, 1);
        assertThat(store.nextSequence(OWNER_ID, begin.conversationId(), runId)).isEqualTo(1);
        store.complete(OWNER_ID, begin.conversationId(), runId, turn, null, now);
        var replay = store.begin(OWNER_ID, begin.conversationId(), messageId, "问题", 1L, UUID.randomUUID(), now);
        assertThat(replay.replayTurn().answer()).isEqualTo("无可靠知识");
        assertThat(store.nextReplaySequence(OWNER_ID, begin.conversationId())).isEqualTo(2);
    }

    /** 同一消息 UUID 用于不同正文时必须拒绝，不得错误重放。 */
    @Test
    void shouldRejectMessageIdReuseWithDifferentContent() {
        UUID messageId = UUID.randomUUID(); UUID runId = UUID.randomUUID();
        var begin = store.begin(OWNER_ID, null, messageId, "问题一", null, runId, Instant.now());
        store.fail(OWNER_ID, begin.conversationId(), runId, Instant.now());
        store.begin(OWNER_ID, begin.conversationId(), messageId, "问题一", 0L, UUID.randomUUID(), Instant.now());
        assertThatThrownBy(() -> store.begin(OWNER_ID, begin.conversationId(), messageId, "问题二", 0L,
                UUID.randomUUID(), Instant.now())).isInstanceOfSatisfying(ApplicationException.class,
                value -> assertThat(value.errorCode()).isEqualTo(ErrorCode.CHAT_MESSAGE_ID_REUSED));
    }

    /** 同一会话已有运行租约时必须拒绝第二个不同请求。 */
    @Test
    void shouldRejectConcurrentRunForSameConversation() {
        UUID firstRunId = UUID.randomUUID();
        var begin = store.begin(OWNER_ID, null, UUID.randomUUID(), "问题一", null,
                firstRunId, Instant.now());

        assertThatThrownBy(() -> store.begin(OWNER_ID, begin.conversationId(), UUID.randomUUID(),
                "问题二", 0L, UUID.randomUUID(), Instant.now()))
                .isInstanceOfSatisfying(ApplicationException.class,
                        value -> assertThat(value.errorCode())
                                .isEqualTo(ErrorCode.CHAT_CONVERSATION_BUSY));
    }

    /** 验证 Redis 数据丢失后旧版本明确过期，而新会话仍可安全开始。 */
    @Test
    void shouldDegradeSafelyAfterRedisDataLoss() {
        Instant now = Instant.now();
        UUID messageId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        var begin = store.begin(OWNER_ID, null, messageId, "问题", null, runId, now);
        CompletedTurn turn = new CompletedTurn(UUID.randomUUID(), messageId, runId, "问题", "问题",
                ChatIntent.SUPPORT_QUERY, "答案", null, List.of(), null,
                null, "GROUNDED", now, 1);
        store.complete(OWNER_ID, begin.conversationId(), runId, turn, null, now);

        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        assertThatThrownBy(() -> store.begin(OWNER_ID, begin.conversationId(), UUID.randomUUID(),
                "继续追问", 1L, UUID.randomUUID(), now.plusSeconds(1)))
                .isInstanceOfSatisfying(ApplicationException.class,
                        value -> assertThat(value.errorCode())
                                .isEqualTo(ErrorCode.CHAT_CONVERSATION_EXPIRED));
        assertThat(store.begin(OWNER_ID, null, UUID.randomUUID(), "重新开始", null,
                UUID.randomUUID(), now.plusSeconds(2)).status())
                .isEqualTo(com.lawrence.supportagent.chat.port.ConversationStorePort.BeginStatus.ACQUIRED);
    }

    /** 摘要 CAS 成功后只裁剪已覆盖旧轮次，竞争失败不得覆盖较新摘要。 */
    @Test
    void shouldCommitSummaryWithCasAndPreserveRecentTurns() {
        UUID conversationId = null;
        long version = 0;
        for (int index = 1; index <= 8; index++) {
            UUID runId = UUID.randomUUID();
            UUID messageId = UUID.randomUUID();
            var begin = store.begin(OWNER_ID, conversationId, messageId, "问题" + index,
                    conversationId == null ? null : version, runId, Instant.now());
            conversationId = begin.conversationId();
            version++;
            store.complete(OWNER_ID, conversationId, runId, new CompletedTurn(UUID.randomUUID(), messageId,
                    runId, "问题" + index, "问题" + index, ChatIntent.SUPPORT_QUERY,
                    "回答" + index, null, List.of(), null, null, "GROUNDED",
                    Instant.now(), version), null, Instant.now());
        }
        ConversationSummary summary = new ConversationSummary(ConversationSummary.SCHEMA_VERSION,
                1, 2, List.of("排查连接问题"), List.of(), List.of(), List.of(), List.of());

        assertThat(store.commitSummary(OWNER_ID, conversationId, 0, summary, 6, Instant.now())).isTrue();
        var snapshot = store.memorySnapshot(OWNER_ID, conversationId);
        assertThat(snapshot.summary()).isEqualTo(summary);
        assertThat(snapshot.turns()).extracting(CompletedTurn::conversationVersion)
                .containsExactly(3L, 4L, 5L, 6L, 7L, 8L);
        assertThat(redisTemplate.getExpire("support-agent:chat:conversation:{" + conversationId + "}"))
                .isBetween(Duration.ofDays(6).toSeconds(), Duration.ofDays(7).toSeconds());
        assertThat(redisTemplate.getExpire("support-agent:chat:turns:{" + conversationId + "}"))
                .isBetween(Duration.ofDays(6).toSeconds(), Duration.ofDays(7).toSeconds());
        assertThat(store.commitSummary(OWNER_ID, conversationId, 0, summary, 6, Instant.now())).isFalse();
    }

    /** 其他用户和旧无归属会话均不得被当前用户接管。 */
    @Test
    void shouldRejectCrossUserAndOwnerlessConversationAccess() {
        UUID conversationId = store.begin(OWNER_ID, null, UUID.randomUUID(), "问题", null,
                UUID.randomUUID(), Instant.now()).conversationId();
        UUID anotherUser = UUID.fromString("30000000-0000-0000-0000-000000000001");

        assertThatThrownBy(() -> store.memorySnapshot(anotherUser, conversationId))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.CHAT_CONVERSATION_EXPIRED));
        redisTemplate.opsForHash().delete(
                "support-agent:chat:conversation:{" + conversationId + "}", "ownerUserId");
        assertThatThrownBy(() -> store.memorySnapshot(OWNER_ID, conversationId))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.CHAT_CONVERSATION_EXPIRED));
    }
}
