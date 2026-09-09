package com.lawrence.supportagent.resolvedcase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.asynctask.AggregateType;
import com.lawrence.supportagent.asynctask.AsyncTask;
import com.lawrence.supportagent.asynctask.AsyncTaskExecutionContext;
import com.lawrence.supportagent.asynctask.AsyncTaskExecutionException;
import com.lawrence.supportagent.asynctask.AsyncTaskType;
import com.lawrence.supportagent.knowledge.DocumentChunker;
import com.lawrence.supportagent.knowledge.DocumentContentPolicy;
import com.lawrence.supportagent.knowledge.port.KnowledgeIndexPort;
import com.lawrence.supportagent.model.EmbeddingModelPort;
import com.lawrence.supportagent.resolvedcase.port.ResolvedCaseRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 验证案例索引任务最终失败时的领域状态回写。 */
class ResolvedCaseIndexTaskHandlerTest {
    private static final Instant NOW = Instant.parse("2026-09-09T01:00:00Z");

    /** 验证耗尽重试后只把对应发布中版本改为发布失败。 */
    @Test
    void shouldMarkPublishingCaseFailedAfterFinalAttempt() {
        ResolvedCaseRepository cases = mock(ResolvedCaseRepository.class);
        ResolvedCase publishing = draft().startPublishing("reviewer", NOW);
        when(cases.findById(1)).thenReturn(Optional.of(publishing));
        ResolvedCaseIndexTaskHandler handler = new ResolvedCaseIndexTaskHandler(cases,
                mock(DocumentContentPolicy.class), mock(DocumentChunker.class),
                mock(EmbeddingModelPort.class), mock(KnowledgeIndexPort.class), () -> NOW);
        AsyncTask task = AsyncTask.pending(AsyncTaskType.KNOWLEDGE_INDEX,
                AggregateType.RESOLVED_CASE, 1, publishing.version(),
                "case-index:1:1", "reviewer", NOW);
        AsyncTaskExecutionException failure = new AsyncTaskExecutionException(
                "KNOWLEDGE_INDEX_FAILED", "索引服务不可用", false);

        handler.finalFailureMutation(new AsyncTaskExecutionContext(task, () -> true), failure)
                .apply();

        ArgumentCaptor<ResolvedCase> captor = ArgumentCaptor.forClass(ResolvedCase.class);
        verify(cases).save(captor.capture());
        assertEquals(ResolvedCaseStatus.PUBLISH_FAILED, captor.getValue().status());
        assertEquals("索引服务不可用", captor.getValue().publishFailureReason());
    }

    /** 创建带持久化主键的待审核案例。 */
    private ResolvedCase draft() {
        return new ResolvedCase(1L, 1, "案例", "问题", "根因", "方案",
                ResolvedCaseStatus.DRAFT, "hash", 0, null, null, null,
                false, null, null, "generator", NOW, "generator", NOW,
                null, null, null, null);
    }
}
