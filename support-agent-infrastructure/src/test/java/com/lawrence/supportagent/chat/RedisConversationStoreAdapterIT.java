package com.lawrence.supportagent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lawrence.supportagent.chat.port.ConversationStorePort.CompletedTurn;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
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
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.7"))
            .withExposedPorts(6379);
    private RedisConversationStoreAdapter store;
    private LettuceConnectionFactory connectionFactory;

    /** 为每个测试创建连接并清空独立容器数据。 */
    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        template.getConnectionFactory().getConnection().serverCommands().flushAll();
        store = new RedisConversationStoreAdapter(template, JsonMapper.builder().findAndAddModules().build());
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
        var begin = store.begin(null, messageId, "问题", null, runId, now);
        UUID suggestionId = UUID.randomUUID();
        CompletedTurn turn = new CompletedTurn(UUID.randomUUID(), messageId, runId, "问题", "问题",
                ChatIntent.SUPPORT_QUERY, "无可靠知识", null, List.of(), suggestionId,
                "冻结上下文", "NO_RELIABLE_KNOWLEDGE", now, 1);
        assertThat(store.nextSequence(begin.conversationId(), runId)).isEqualTo(1);
        store.complete(begin.conversationId(), runId, turn, null, now);
        var replay = store.begin(begin.conversationId(), messageId, "问题", 1L, UUID.randomUUID(), now);
        assertThat(replay.replayTurn().answer()).isEqualTo("无可靠知识");
        assertThat(store.nextReplaySequence(begin.conversationId())).isEqualTo(2);
    }

    /** 同一消息 UUID 用于不同正文时必须拒绝，不得错误重放。 */
    @Test
    void shouldRejectMessageIdReuseWithDifferentContent() {
        UUID messageId = UUID.randomUUID(); UUID runId = UUID.randomUUID();
        var begin = store.begin(null, messageId, "问题一", null, runId, Instant.now());
        store.fail(begin.conversationId(), runId, Instant.now());
        store.begin(begin.conversationId(), messageId, "问题一", 0L, UUID.randomUUID(), Instant.now());
        assertThatThrownBy(() -> store.begin(begin.conversationId(), messageId, "问题二", 0L,
                UUID.randomUUID(), Instant.now())).isInstanceOfSatisfying(ApplicationException.class,
                value -> assertThat(value.errorCode()).isEqualTo(ErrorCode.CHAT_MESSAGE_ID_REUSED));
    }

    /** 同一会话已有运行租约时必须拒绝第二个不同请求。 */
    @Test
    void shouldRejectConcurrentRunForSameConversation() {
        UUID firstRunId = UUID.randomUUID();
        var begin = store.begin(null, UUID.randomUUID(), "问题一", null,
                firstRunId, Instant.now());

        assertThatThrownBy(() -> store.begin(begin.conversationId(), UUID.randomUUID(),
                "问题二", 0L, UUID.randomUUID(), Instant.now()))
                .isInstanceOfSatisfying(ApplicationException.class,
                        value -> assertThat(value.errorCode())
                                .isEqualTo(ErrorCode.CHAT_CONVERSATION_BUSY));
    }
}
