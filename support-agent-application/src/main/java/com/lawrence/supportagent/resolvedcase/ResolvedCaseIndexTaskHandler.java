package com.lawrence.supportagent.resolvedcase;

import com.lawrence.supportagent.asynctask.AggregateType;
import com.lawrence.supportagent.asynctask.AsyncTask;
import com.lawrence.supportagent.asynctask.AsyncTaskBusinessMutation;
import com.lawrence.supportagent.asynctask.AsyncTaskCancelledException;
import com.lawrence.supportagent.asynctask.AsyncTaskExecutionContext;
import com.lawrence.supportagent.asynctask.AsyncTaskExecutionException;
import com.lawrence.supportagent.asynctask.AsyncTaskHandler;
import com.lawrence.supportagent.asynctask.AsyncTaskType;
import com.lawrence.supportagent.knowledge.DocumentChunker;
import com.lawrence.supportagent.knowledge.DocumentContentPolicy;
import com.lawrence.supportagent.knowledge.DocumentInputType;
import com.lawrence.supportagent.knowledge.ExactTermExtractor;
import com.lawrence.supportagent.knowledge.IndexedKnowledgeChunk;
import com.lawrence.supportagent.knowledge.KnowledgeChunkDraft;
import com.lawrence.supportagent.knowledge.port.KnowledgeIndexException;
import com.lawrence.supportagent.knowledge.port.KnowledgeIndexPort;
import com.lawrence.supportagent.model.EmbeddingModelPort;
import com.lawrence.supportagent.model.ModelInvocationException;
import com.lawrence.supportagent.resolvedcase.port.ResolvedCaseRepository;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 把人工审核通过的已解决案例写入统一知识索引。 */
public class ResolvedCaseIndexTaskHandler implements AsyncTaskHandler {
    private static final String SOURCE_TYPE = "RESOLVED_CASE";
    private final ResolvedCaseRepository cases;
    private final DocumentContentPolicy policy;
    private final DocumentChunker chunker;
    private final EmbeddingModelPort embeddings;
    private final KnowledgeIndexPort index;
    private final TimeProvider time;

    /** 注入案例、内容策略、分块、向量、索引和时间端口。 */
    public ResolvedCaseIndexTaskHandler(ResolvedCaseRepository cases,
                                        DocumentContentPolicy policy,
                                        DocumentChunker chunker,
                                        EmbeddingModelPort embeddings,
                                        KnowledgeIndexPort index, TimeProvider time) {
        this.cases = cases;
        this.policy = policy;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.index = index;
        this.time = time;
    }

    /** {@inheritDoc} */
    @Override public AsyncTaskType taskType() { return AsyncTaskType.KNOWLEDGE_INDEX; }

    /** {@inheritDoc} */
    @Override public boolean supports(AsyncTask task) {
        return task != null && task.taskType() == taskType()
                && task.aggregateType() == AggregateType.RESOLVED_CASE;
    }

    /** {@inheritDoc} */
    @Override
    public AsyncTaskBusinessMutation execute(AsyncTaskExecutionContext context) {
        AsyncTask task = context.task();
        ResolvedCase value = current(task, ResolvedCaseStatus.PUBLISHING);
        String content = content(value);
        long publishedVersion;
        try {
            policy.verifyNoSensitiveContent(content);
            List<KnowledgeChunkDraft> drafts = chunker.chunk(value.title(),
                    DocumentInputType.DIRECT_TEXT, content);
            List<List<Double>> vectors = embeddings.embedDocuments(
                    drafts.stream().map(KnowledgeChunkDraft::content).toList(),
                    () -> requireLease(context));
            validateVectors(drafts.size(), vectors);
            publishedVersion = Math.addExact(task.aggregateVersion(), 1);
            Instant publishedAt = time.now();
            List<IndexedKnowledgeChunk> chunks = chunks(value, drafts, vectors,
                    publishedVersion, publishedAt);
            write(task, publishedVersion, chunks);
            return () -> cases.save(current(task, ResolvedCaseStatus.PUBLISHING)
                    .publish(task.createdBy(), publishedAt));
        } catch (ModelInvocationException exception) {
            throw new AsyncTaskExecutionException(exception.errorCode(), exception.getMessage(),
                    exception.retryable());
        } catch (KnowledgeIndexException exception) {
            throw new AsyncTaskExecutionException(exception.errorCode(), exception.getMessage(),
                    exception.retryable());
        } catch (RuntimeException exception) {
            if (exception instanceof AsyncTaskExecutionException failure) throw failure;
            throw new AsyncTaskExecutionException("CASE_INDEX_FAILED", "案例知识索引失败", false);
        }
    }

    /** {@inheritDoc} */
    @Override
    public AsyncTaskBusinessMutation finalFailureMutation(
            AsyncTaskExecutionContext context, AsyncTaskExecutionException failure) {
        AsyncTask task = context.task();
        String message = failure.getMessage() == null ? "案例发布失败" : failure.getMessage();
        String safe = message.length() <= 1000 ? message : message.substring(0, 1000);
        return () -> cases.findById(task.aggregateId()).filter(value ->
                        value.status() == ResolvedCaseStatus.PUBLISHING
                                && value.version() == task.aggregateVersion())
                .ifPresent(value -> cases.save(value.failPublishing(safe,
                        task.createdBy(), time.now())));
    }

    /** 读取任务绑定版本和状态的案例。 */
    private ResolvedCase current(AsyncTask task, ResolvedCaseStatus status) {
        return cases.findById(task.aggregateId()).filter(value -> !value.deleted()
                        && value.status() == status && value.version() == task.aggregateVersion())
                .orElseThrow(() -> new AsyncTaskCancelledException(
                        "案例已不存在或版本状态已变化", AsyncTaskBusinessMutation.NONE));
    }

    /** 按固定 Markdown 结构组合四个经审核字段。 */
    private String content(ResolvedCase value) {
        return "# " + value.title() + "\n\n## 问题\n" + value.problem()
                + "\n\n## 根因\n" + value.cause() + "\n\n## 解决方案\n" + value.solution();
    }

    /** 校验每批模型调用前仍持有任务租约。 */
    private void requireLease(AsyncTaskExecutionContext context) {
        if (!context.renewLease()) throw new AsyncTaskExecutionException(
                "ASYNC_TASK_LEASE_LOST", "异步任务租约已失效", true);
    }

    /** 校验向量数量、维度及数值。 */
    private void validateVectors(int count, List<List<Double>> values) {
        if (values == null || values.size() != count || values.stream().anyMatch(vector ->
                vector == null || vector.size() != 1024 || vector.stream().anyMatch(
                        number -> number == null || !Double.isFinite(number)))) {
            throw new AsyncTaskExecutionException("EMBEDDING_RESULT_INVALID",
                    "Embedding 返回数量、维度或数值不合法", false);
        }
    }

    /** 构造案例版本对应的确定性索引分块。 */
    private List<IndexedKnowledgeChunk> chunks(ResolvedCase value,
                                                List<KnowledgeChunkDraft> drafts,
                                                List<List<Double>> vectors,
                                                long version, Instant publishedAt) {
        List<IndexedKnowledgeChunk> result = new ArrayList<>();
        for (int position = 0; position < drafts.size(); position++) {
            KnowledgeChunkDraft draft = drafts.get(position);
            result.add(new IndexedKnowledgeChunk(SOURCE_TYPE + ":" + value.id() + ":"
                    + version + ":" + draft.chunkIndex(), SOURCE_TYPE, value.id(), version,
                    draft.chunkIndex(), value.title(), draft.headingPath(), draft.content(),
                    draft.exactTerms(), draft.contentHash(), vectors.get(position), publishedAt,
                    time.now(), DocumentChunker.VERSION, ExactTermExtractor.VERSION));
        }
        return List.copyOf(result);
    }

    /** 写入并校验完整版本，失败时清理部分分块。 */
    private void write(AsyncTask task, long version, List<IndexedKnowledgeChunk> chunks) {
        boolean writeStarted = false;
        try {
            index.ensureReady();
            writeStarted = true;
            index.indexChunks(chunks);
            if (!index.verifyVersion(SOURCE_TYPE, task.aggregateId(), version,
                    chunks.stream().map(IndexedKnowledgeChunk::contentHash).toList())) {
                throw new KnowledgeIndexException("KNOWLEDGE_INDEX_INCOMPLETE",
                        "案例索引完整性校验失败", true, null);
            }
        } catch (KnowledgeIndexException exception) {
            if (writeStarted) {
                try {
                    index.deleteVersion(SOURCE_TYPE, task.aggregateId(), version);
                } catch (KnowledgeIndexException cleanup) {
                    throw new KnowledgeIndexException("KNOWLEDGE_INDEX_CLEANUP_FAILED",
                            "案例部分索引清理失败", true, cleanup);
                }
            }
            throw exception;
        }
    }
}
