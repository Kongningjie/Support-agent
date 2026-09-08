package com.lawrence.supportagent.knowledge;

import com.lawrence.supportagent.asynctask.AggregateType;
import com.lawrence.supportagent.asynctask.AsyncTask;
import com.lawrence.supportagent.asynctask.AsyncTaskBusinessMutation;
import com.lawrence.supportagent.asynctask.AsyncTaskCancelledException;
import com.lawrence.supportagent.asynctask.AsyncTaskExecutionContext;
import com.lawrence.supportagent.asynctask.AsyncTaskExecutionException;
import com.lawrence.supportagent.asynctask.AsyncTaskHandler;
import com.lawrence.supportagent.asynctask.AsyncTaskType;
import com.lawrence.supportagent.knowledge.port.KnowledgeIndexException;
import com.lawrence.supportagent.knowledge.port.KnowledgeIndexPort;
import com.lawrence.supportagent.knowledge.port.ManagedDocumentRepository;
import com.lawrence.supportagent.model.EmbeddingModelPort;
import com.lawrence.supportagent.model.ModelInvocationException;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 执行托管文档分块、向量化、完整索引及最终状态回写。 */
public class KnowledgeIndexTaskHandler implements AsyncTaskHandler {
    private static final String SOURCE_TYPE = "MANAGED_DOCUMENT";
    private static final int EMBEDDING_DIMENSIONS = 1024;
    private final ManagedDocumentRepository documentRepository;
    private final DocumentContentPolicy contentPolicy;
    private final DocumentChunker chunker;
    private final EmbeddingModelPort embeddingModel;
    private final KnowledgeIndexPort indexPort;
    private final TimeProvider timeProvider;

    /** 注入文档、内容策略、分块、模型、索引和时间端口。 */
    public KnowledgeIndexTaskHandler(ManagedDocumentRepository documentRepository,
                                     DocumentContentPolicy contentPolicy,
                                     DocumentChunker chunker,
                                     EmbeddingModelPort embeddingModel,
                                     KnowledgeIndexPort indexPort,
                                     TimeProvider timeProvider) {
        this.documentRepository = documentRepository;
        this.contentPolicy = contentPolicy;
        this.chunker = chunker;
        this.embeddingModel = embeddingModel;
        this.indexPort = indexPort;
        this.timeProvider = timeProvider;
    }

    /** {@inheritDoc} */
    @Override
    public AsyncTaskType taskType() {
        return AsyncTaskType.KNOWLEDGE_INDEX;
    }

    /** {@inheritDoc} */
    @Override
    public AsyncTaskBusinessMutation execute(AsyncTaskExecutionContext context) {
        AsyncTask task = requireManagedDocumentTask(context.task());
        ManagedDocument document = currentIndexingDocument(task);
        try {
            contentPolicy.verifyNoSensitiveContent(document.rawContent());
            List<KnowledgeChunkDraft> drafts = chunker.chunk(document.title(),
                    document.inputType(), document.rawContent());
            if (drafts.isEmpty()) {
                throw new AsyncTaskExecutionException(
                        "KNOWLEDGE_EMPTY_CHUNKS", "文档没有产生可索引分块", false);
            }
            List<List<Double>> embeddings = embeddingModel.embedDocuments(
                    drafts.stream().map(KnowledgeChunkDraft::content).toList(),
                    () -> requireLease(context));
            validateEmbeddings(drafts.size(), embeddings);
            long publishedVersion = Math.addExact(task.aggregateVersion(), 1);
            Instant publicationTime = timeProvider.now();
            List<IndexedKnowledgeChunk> chunks = indexedChunks(document, publishedVersion,
                    publicationTime, drafts, embeddings);
            writeAndVerify(task, publishedVersion, chunks);
            return () -> publishIfCurrent(task, publicationTime);
        } catch (ModelInvocationException exception) {
            throw new AsyncTaskExecutionException(exception.errorCode(),
                    exception.getMessage(), exception.retryable());
        } catch (KnowledgeIndexException exception) {
            throw new AsyncTaskExecutionException(exception.errorCode(),
                    exception.getMessage(), exception.retryable());
        } catch (ApplicationException exception) {
            throw new AsyncTaskExecutionException(exception.errorCode().name(),
                    exception.getMessage(), false);
        } catch (ArithmeticException exception) {
            throw new AsyncTaskExecutionException(
                    "KNOWLEDGE_VERSION_EXHAUSTED", "文档版本号已经耗尽", false);
        }
    }

    /** {@inheritDoc} */
    @Override
    public AsyncTaskBusinessMutation finalFailureMutation(
            AsyncTaskExecutionContext context, AsyncTaskExecutionException failure) {
        AsyncTask task = context.task();
        if (task.aggregateType() != AggregateType.MANAGED_DOCUMENT) {
            return AsyncTaskBusinessMutation.NONE;
        }
        String safeReason = truncate(failure.getMessage(), 1000);
        return () -> failIfCurrent(task, safeReason, timeProvider.now());
    }

    /** 校验任务只关联托管文档。 */
    private AsyncTask requireManagedDocumentTask(AsyncTask task) {
        if (task.aggregateType() != AggregateType.MANAGED_DOCUMENT) {
            throw new AsyncTaskExecutionException(
                    "KNOWLEDGE_AGGREGATE_UNSUPPORTED", "索引任务关联对象类型不受支持", false);
        }
        return task;
    }

    /** 读取任务创建时版本的索引中文档，失效任务安全取消。 */
    private ManagedDocument currentIndexingDocument(AsyncTask task) {
        return documentRepository.findById(task.aggregateId())
                .filter(value -> !value.deleted()
                        && value.status() == ManagedDocumentStatus.INDEXING
                        && value.version() == task.aggregateVersion())
                .orElseThrow(() -> new AsyncTaskCancelledException(
                        "文档已不存在或版本状态已变化", AsyncTaskBusinessMutation.NONE));
    }

    /** 在模型每批调用前确认当前 Worker 仍持有任务。 */
    private void requireLease(AsyncTaskExecutionContext context) {
        if (!context.renewLease()) {
            throw new AsyncTaskExecutionException(
                    "ASYNC_TASK_LEASE_LOST", "异步任务租约已失效", true);
        }
    }

    /** 严格校验向量数量、顺序位置、维度和有限数值。 */
    private void validateEmbeddings(int expectedCount, List<List<Double>> embeddings) {
        if (embeddings == null || embeddings.size() != expectedCount) {
            throw new AsyncTaskExecutionException(
                    "EMBEDDING_RESULT_MISMATCH", "Embedding 返回数量与输入不一致", false);
        }
        for (List<Double> embedding : embeddings) {
            if (embedding == null || embedding.size() != EMBEDDING_DIMENSIONS
                    || embedding.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
                throw new AsyncTaskExecutionException(
                        "EMBEDDING_RESULT_INVALID", "Embedding 返回向量维度或数值不合法", false);
            }
        }
    }

    /** 把分块和同位置向量组装为完整索引文档。 */
    private List<IndexedKnowledgeChunk> indexedChunks(
            ManagedDocument document, long publishedVersion, Instant publicationTime,
            List<KnowledgeChunkDraft> drafts, List<List<Double>> embeddings) {
        List<IndexedKnowledgeChunk> chunks = new ArrayList<>();
        for (int index = 0; index < drafts.size(); index++) {
            KnowledgeChunkDraft draft = drafts.get(index);
            String chunkId = SOURCE_TYPE + ":" + document.id() + ":"
                    + publishedVersion + ":" + draft.chunkIndex();
            chunks.add(new IndexedKnowledgeChunk(chunkId, SOURCE_TYPE, document.id(),
                    publishedVersion, draft.chunkIndex(), document.title(), draft.headingPath(),
                    draft.content(), draft.exactTerms(), draft.contentHash(), embeddings.get(index),
                    publicationTime, timeProvider.now(), DocumentChunker.VERSION,
                    ExactTermExtractor.VERSION));
        }
        return List.copyOf(chunks);
    }

    /** 写入全部分块、逐项检查并按数量和哈希验证完整性。 */
    private void writeAndVerify(AsyncTask task, long publishedVersion,
                                List<IndexedKnowledgeChunk> chunks) {
        boolean writeStarted = false;
        try {
            indexPort.ensureReady();
            writeStarted = true;
            indexPort.indexChunks(chunks);
            List<String> hashes = chunks.stream()
                    .map(IndexedKnowledgeChunk::contentHash).toList();
            if (!indexPort.verifyVersion(SOURCE_TYPE, task.aggregateId(),
                    publishedVersion, hashes)) {
                throw new KnowledgeIndexException("KNOWLEDGE_INDEX_INCOMPLETE",
                        "Elasticsearch 分块完整性校验失败", true, null);
            }
        } catch (KnowledgeIndexException exception) {
            if (writeStarted) {
                cleanupPartialVersion(task, publishedVersion);
            }
            throw exception;
        }
    }

    /** 删除本次来源版本的部分写入结果，清理失败则保留可重试错误。 */
    private void cleanupPartialVersion(AsyncTask task, long publishedVersion) {
        try {
            indexPort.deleteVersion(SOURCE_TYPE, task.aggregateId(), publishedVersion);
        } catch (KnowledgeIndexException exception) {
            throw new KnowledgeIndexException("KNOWLEDGE_INDEX_CLEANUP_FAILED",
                    "Elasticsearch 部分索引清理失败", true, exception);
        }
    }

    /** 在任务成功事务中把仍匹配的文档切换为最终发布版本。 */
    private void publishIfCurrent(AsyncTask task, Instant publicationTime) {
        ManagedDocument current = currentIndexingDocument(task);
        documentRepository.save(current.publish(task.createdBy(), publicationTime));
    }

    /** 在任务死亡事务中把仍匹配的文档标记为索引失败。 */
    private void failIfCurrent(AsyncTask task, String reason, Instant failedAt) {
        documentRepository.findById(task.aggregateId())
                .filter(value -> !value.deleted()
                        && value.status() == ManagedDocumentStatus.INDEXING
                        && value.version() == task.aggregateVersion())
                .ifPresent(value -> documentRepository.save(value.failIndexing(
                        reason, task.createdBy(), failedAt)));
    }

    /** 截断安全错误摘要以满足数据库字段上限。 */
    private String truncate(String value, int maxLength) {
        String safe = value == null || value.isBlank() ? "知识索引失败" : value;
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
    }
}
