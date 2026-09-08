package com.lawrence.supportagent.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.asynctask.AggregateType;
import com.lawrence.supportagent.asynctask.AsyncTask;
import com.lawrence.supportagent.asynctask.AsyncTaskBusinessMutation;
import com.lawrence.supportagent.asynctask.AsyncTaskExecutionContext;
import com.lawrence.supportagent.asynctask.AsyncTaskExecutionException;
import com.lawrence.supportagent.asynctask.AsyncTaskStatus;
import com.lawrence.supportagent.asynctask.AsyncTaskType;
import com.lawrence.supportagent.knowledge.port.KnowledgeIndexPort;
import com.lawrence.supportagent.knowledge.port.ManagedDocumentRepository;
import com.lawrence.supportagent.model.EmbeddingModelPort;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 验证知识索引 Handler 的版本、完整性和最终业务状态动作。 */
class KnowledgeIndexTaskHandlerTest {
    private static final Instant NOW = Instant.parse("2026-09-07T01:00:00Z");

    /** 验证成功写入使用最终发布版本，并由事务动作把文档切为 PUBLISHED。 */
    @Test
    void shouldIndexFinalVersionAndPublishInCompletionMutation() {
        AtomicReference<ManagedDocument> document = new AtomicReference<>(indexingDocument());
        ManagedDocumentRepository repository = repository(document);
        EmbeddingModelPort embedding = mock(EmbeddingModelPort.class);
        when(embedding.embedDocuments(any(), any())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(ignored -> Collections.nCopies(1024, 0.01D)).toList();
        });
        KnowledgeIndexPort indexPort = mock(KnowledgeIndexPort.class);
        when(indexPort.verifyVersion(any(), anyLong(), anyLong(), any()))
                .thenReturn(true);
        KnowledgeIndexTaskHandler handler = handler(repository, embedding, indexPort);

        AsyncTaskBusinessMutation mutation = handler.execute(context(task()));
        mutation.apply();

        ArgumentCaptor<List<IndexedKnowledgeChunk>> chunks = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(indexPort).indexChunks(chunks.capture());
        assertTrue(chunks.getValue().stream().allMatch(chunk -> chunk.sourceVersion() == 2));
        assertEquals(ManagedDocumentStatus.PUBLISHED, document.get().status());
        assertEquals(2, document.get().version());
    }

    /** 验证 Embedding 维度错误不可重试，最终动作把匹配文档标记为 FAILED。 */
    @Test
    void shouldFailDocumentAfterInvalidEmbeddingBecomesDead() {
        AtomicReference<ManagedDocument> document = new AtomicReference<>(indexingDocument());
        ManagedDocumentRepository repository = repository(document);
        EmbeddingModelPort embedding = mock(EmbeddingModelPort.class);
        when(embedding.embedDocuments(any(), any()))
                .thenReturn(List.of(Collections.nCopies(3, 0.01D)));
        KnowledgeIndexTaskHandler handler = handler(repository, embedding,
                mock(KnowledgeIndexPort.class));

        AsyncTaskExecutionException failure = assertThrows(AsyncTaskExecutionException.class,
                () -> handler.execute(context(task())));
        handler.finalFailureMutation(context(task()), failure).apply();

        assertEquals(false, failure.retryable());
        assertEquals(ManagedDocumentStatus.FAILED, document.get().status());
        assertEquals(2, document.get().version());
    }

    /** 创建使用真实策略和冻结时间的 Handler。 */
    private KnowledgeIndexTaskHandler handler(ManagedDocumentRepository repository,
                                               EmbeddingModelPort embedding,
                                               KnowledgeIndexPort indexPort) {
        return new KnowledgeIndexTaskHandler(repository, new DocumentContentPolicy(),
                new DocumentChunker(new ExactTermExtractor()), embedding, indexPort, () -> NOW);
    }

    /** 创建可原地观察业务终态动作的仓储 Mock。 */
    private ManagedDocumentRepository repository(AtomicReference<ManagedDocument> document) {
        ManagedDocumentRepository repository = mock(ManagedDocumentRepository.class);
        when(repository.findById(7L)).thenAnswer(ignored -> Optional.of(document.get()));
        when(repository.save(any())).thenAnswer(invocation -> {
            ManagedDocument saved = invocation.getArgument(0);
            document.set(saved);
            return saved;
        });
        return repository;
    }

    /** 创建 ID 已分配且版本为 1 的索引中文档。 */
    private ManagedDocument indexingDocument() {
        ManagedDocument draft = new ManagedDocument(7L, "排障", DocumentInputType.DIRECT_TEXT,
                null, "text/plain", "检查 ERROR_CONNECTION。",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                ManagedDocumentStatus.DRAFT, 0, null, null, false, null, null,
                "tester", NOW.minusSeconds(2), "tester", NOW.minusSeconds(2),
                null, null, null, null);
        return draft.startIndexing("tester", NOW.minusSeconds(1));
    }

    /** 创建与索引中文档版本严格匹配的运行中任务。 */
    private AsyncTask task() {
        return new AsyncTask(11L, AsyncTaskType.KNOWLEDGE_INDEX,
                AggregateType.MANAGED_DOCUMENT, 7L, 1L, "knowledge-index:7:1",
                AsyncTaskStatus.RUNNING, 1, 3, NOW, "worker", NOW.plusSeconds(300),
                null, null, null, null, "tester", NOW.minusSeconds(1), NOW, null, NOW);
    }

    /** 创建续租始终成功的执行上下文。 */
    private AsyncTaskExecutionContext context(AsyncTask task) {
        return new AsyncTaskExecutionContext(task, () -> true);
    }
}
