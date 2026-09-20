package com.lawrence.supportagent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.chat.port.ConversationStorePort;
import com.lawrence.supportagent.chat.port.ConversationStorePort.CompletedTurn;
import com.lawrence.supportagent.chat.port.ConversationStorePort.MemorySnapshot;
import com.lawrence.supportagent.knowledge.ExactTermExtractor;
import com.lawrence.supportagent.knowledge.ExactTermType;
import com.lawrence.supportagent.knowledge.DocumentContentPolicy;
import com.lawrence.supportagent.model.ConversationSummaryPort;
import com.lawrence.supportagent.model.ModelInvocationException;
import com.lawrence.supportagent.retrieval.RetrievalStatus;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 验证滚动摘要触发、实体校验、有限重试和预算内上下文组装。 */
@ExtendWith(MockitoExtension.class)
class ConversationContextServiceTest {
    private static final UUID OWNER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID CONVERSATION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    @Mock private ConversationStorePort store;
    @Mock private ConversationSummaryPort model;
    private ConversationContextService service;

    /** 使用同步执行器建立可确定验证软触发行为的服务。 */
    @BeforeEach
    void setUp() {
        Executor direct = Runnable::run;
        service = new ConversationContextService(store, model, new ExactTermExtractor(),
                new DocumentContentPolicy(), new ConservativeTokenEstimator(), settings(), direct,
                () -> Instant.EPOCH);
    }

    /** 未达到阈值时应按时间顺序返回完整轮次且不调用摘要模型。 */
    @Test
    void shouldAssembleRecentTurnsWithoutSummary() {
        when(store.memorySnapshot(OWNER_ID, CONVERSATION_ID)).thenReturn(snapshot(null, turns(3)));
        ConversationContext context = service.prepare(OWNER_ID, CONVERSATION_ID, List.of("当前问题"));
        assertThat(context.modelContext()).hasSize(3);
        assertThat(context.modelContext().getFirst()).contains("问题1");
        verify(model, never()).summarize(any(), any(), any(Long.class), any(Long.class));
    }

    /** 达到硬阈值时应同步摘要较早轮次并在 CAS 后使用摘要和最近六轮。 */
    @Test
    void shouldSynchronouslySummarizeAtHardLimit() {
        List<CompletedTurn> twenty = turns(20);
        ConversationSummary summary = summary(1, 14, List.of());
        when(store.memorySnapshot(OWNER_ID, CONVERSATION_ID)).thenReturn(snapshot(null, twenty),
                snapshot(summary, twenty.subList(14, 20)));
        when(model.summarize(eq(null), any(), eq(1L), eq(14L))).thenReturn(summary);
        when(store.commitSummary(eq(OWNER_ID), eq(CONVERSATION_ID), eq(0L), eq(summary), eq(6), any()))
                .thenReturn(true);

        ConversationContext context = service.prepare(OWNER_ID, CONVERSATION_ID, List.of("当前问题"));

        assertThat(context.summaryVersion()).isEqualTo(1);
        assertThat(context.modelContext()).hasSize(7);
        assertThat(context.modelContext().getFirst()).contains("较早会话摘要");
        verify(store).commitSummary(eq(OWNER_ID), eq(CONVERSATION_ID), eq(0L), eq(summary), eq(6), any());
    }

    /** 无来源关键实体必须使同步摘要失败且不得提交 Redis。 */
    @Test
    void shouldRejectHallucinatedEntity() {
        List<CompletedTurn> twenty = turns(20);
        ConversationSummary invalid = summary(1, 14, List.of(
                new ConversationSummaryKeyEntity(ExactTermType.ERROR_CODE,
                        "UNKNOWN_ERROR", List.of(1L))));
        when(store.memorySnapshot(OWNER_ID, CONVERSATION_ID)).thenReturn(snapshot(null, twenty));
        when(model.summarize(eq(null), any(), eq(1L), eq(14L))).thenReturn(invalid);

        assertThatThrownBy(() -> service.prepare(OWNER_ID, CONVERSATION_ID, List.of("当前问题")))
                .isInstanceOf(ApplicationException.class);
        verify(store, never()).commitSummary(any(), any(), any(Long.class), any(), any(Integer.class), any());
    }

    /** 临时模型故障应只重试一次并在第二次成功后提交候选摘要。 */
    @Test
    void shouldRetryTransientSummaryFailureOnce() {
        List<CompletedTurn> twelve = turns(12);
        ConversationSummary summary = summary(1, 6, List.of());
        when(store.memorySnapshot(OWNER_ID, CONVERSATION_ID)).thenReturn(snapshot(null, twelve));
        when(model.summarize(eq(null), any(), eq(1L), eq(6L)))
                .thenThrow(new ModelInvocationException("TEMP", "临时失败", true, null))
                .thenReturn(summary);
        when(store.commitSummary(any(), any(), any(Long.class), any(), any(Integer.class), any()))
                .thenReturn(true);

        service.afterSuccessfulTurn(OWNER_ID, CONVERSATION_ID);

        verify(model, org.mockito.Mockito.times(2))
                .summarize(eq(null), any(), eq(1L), eq(6L));
        verify(store).commitSummary(eq(OWNER_ID), eq(CONVERSATION_ID), eq(0L), eq(summary), eq(6), any());
    }

    /** Schema 等确定性模型错误不得使用相同输入盲目重试或提交摘要。 */
    @Test
    void shouldNotRetryInvalidSummarySchema() {
        List<CompletedTurn> twenty = turns(20);
        when(store.memorySnapshot(OWNER_ID, CONVERSATION_ID)).thenReturn(snapshot(null, twenty));
        when(model.summarize(eq(null), any(), eq(1L), eq(14L)))
                .thenThrow(new ModelInvocationException(
                        "SUMMARY_SCHEMA_INVALID", "结构不合法", false, null));

        assertThatThrownBy(() -> service.prepare(OWNER_ID, CONVERSATION_ID, List.of("当前问题")))
                .isInstanceOf(ApplicationException.class);
        verify(model).summarize(eq(null), any(), eq(1L), eq(14L));
        verify(store, never()).commitSummary(any(), any(), any(Long.class), any(), any(Integer.class), any());
    }

    /** 硬阈值 CAS 冲突且没有观察到更新摘要时必须安全失败，不得静默丢弃旧轮次。 */
    @Test
    void shouldFailHardCompressionWhenCasMakesNoProgress() {
        List<CompletedTurn> twenty = turns(20);
        ConversationSummary summary = summary(1, 14, List.of());
        when(store.memorySnapshot(OWNER_ID, CONVERSATION_ID)).thenReturn(snapshot(null, twenty),
                snapshot(null, twenty));
        when(model.summarize(eq(null), any(), eq(1L), eq(14L))).thenReturn(summary);
        when(store.commitSummary(eq(OWNER_ID), eq(CONVERSATION_ID), eq(0L), eq(summary), eq(6), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.prepare(OWNER_ID, CONVERSATION_ID, List.of("当前问题")))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("无法继续压缩");
    }

    /** 摘要模型返回疑似凭据时必须拒绝提交，原始成功轮次继续保留。 */
    @Test
    void shouldRejectSensitiveCredentialInSummary() {
        List<CompletedTurn> twenty = turns(20);
        ConversationSummary sensitive = new ConversationSummary(ConversationSummary.SCHEMA_VERSION,
                1, 14, List.of("api_key=abcdefghijklmnop"), List.of(), List.of(),
                List.of(), List.of());
        when(store.memorySnapshot(OWNER_ID, CONVERSATION_ID)).thenReturn(snapshot(null, twenty));
        when(model.summarize(eq(null), any(), eq(1L), eq(14L))).thenReturn(sensitive);

        assertThatThrownBy(() -> service.prepare(OWNER_ID, CONVERSATION_ID, List.of("当前问题")))
                .isInstanceOf(ApplicationException.class);
        verify(store, never()).commitSummary(any(), any(), any(Long.class), any(), any(Integer.class), any());
    }

    /** 固定问题和证据本身超过预算时必须安全拒绝而不是删除安全内容。 */
    @Test
    void shouldRejectFixedSectionsBeyondBudget() {
        ConversationContextService small = new ConversationContextService(store, model,
                new ExactTermExtractor(), new DocumentContentPolicy(), new ConservativeTokenEstimator(),
                new ConversationMemorySettings(20, 5, 5, 1, 2, 10, 3), Runnable::run,
                () -> Instant.EPOCH);
        when(store.memorySnapshot(OWNER_ID, CONVERSATION_ID)).thenReturn(snapshot(null, List.of()));
        assertThatThrownBy(() -> small.prepare(OWNER_ID, CONVERSATION_ID,
                List.of("这是一个明显超过预算且不能截断的当前问题")))
                .isInstanceOf(ApplicationException.class);
    }

    /** 创建冻结默认参数。 */
    private ConversationMemorySettings settings() {
        return new ConversationMemorySettings(24_000, 1_200, 1_024, 6, 12, 6_000, 20);
    }

    /** 创建指定摘要和轮次的稳定内存快照。 */
    private MemorySnapshot snapshot(ConversationSummary summary, List<CompletedTurn> turns) {
        long version = turns.isEmpty() ? (summary == null ? 0 : summary.coveredThroughVersion())
                : turns.getLast().conversationVersion();
        return new MemorySnapshot(CONVERSATION_ID, version,
                summary == null ? 0 : summary.summaryVersion(), summary, turns);
    }

    /** 创建从一开始连续递增的成功轮次。 */
    private List<CompletedTurn> turns(int count) {
        return java.util.stream.LongStream.rangeClosed(1, count).mapToObj(this::turn).toList();
    }

    /** 创建包含确定版本和无敏感正文的成功轮次。 */
    private CompletedTurn turn(long version) {
        return new CompletedTurn(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "问题" + version, "问题" + version, ChatIntent.SUPPORT_QUERY,
                "回答" + version, RetrievalStatus.GROUNDED, List.of(), null, null,
                "GROUNDED", Instant.EPOCH, version);
    }

    /** 创建结构合法且内容为空的测试摘要。 */
    private ConversationSummary summary(long summaryVersion, long covered,
                                        List<ConversationSummaryKeyEntity> entities) {
        return new ConversationSummary(ConversationSummary.SCHEMA_VERSION, summaryVersion,
                covered, List.of(), List.of(), List.of(), List.of(), entities);
    }
}
