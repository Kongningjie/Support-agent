package com.lawrence.supportagent.knowledge;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.knowledge.port.KnowledgeIndexRebuildPort;
import com.lawrence.supportagent.knowledge.port.ManagedDocumentRepository;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpace;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceTelemetryPort;
import com.lawrence.supportagent.knowledgespace.port.KnowledgeSpaceRepository;
import com.lawrence.supportagent.model.EmbeddingModelPort;
import com.lawrence.supportagent.resolvedcase.ResolvedCase;
import com.lawrence.supportagent.resolvedcase.ResolvedCaseStatus;
import com.lawrence.supportagent.resolvedcase.port.ResolvedCaseRepository;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 从 MySQL 当前已发布来源重建新物理索引，完整核对后原子切换别名。 */
public class KnowledgeIndexRebuildUseCase {
    private static final int PAGE_SIZE = 100;
    private final ManagedDocumentRepository documents;
    private final ResolvedCaseRepository cases;
    private final KnowledgeSpaceRepository spaces;
    private final DocumentChunker chunker;
    private final EmbeddingModelPort embeddings;
    private final KnowledgeIndexRebuildPort index;
    private final TimeProvider time;
    private final KnowledgeSpaceTelemetryPort telemetry;

    /** 注入来源事实、空间、分块、向量、索引和时间端口。 */
    public KnowledgeIndexRebuildUseCase(ManagedDocumentRepository documents,
                                        ResolvedCaseRepository cases,
                                        KnowledgeSpaceRepository spaces,
                                        DocumentChunker chunker,
                                        EmbeddingModelPort embeddings,
                                        KnowledgeIndexRebuildPort index,
                                        TimeProvider time) {
        this(documents, cases, spaces, chunker, embeddings, index, time,
                KnowledgeSpaceTelemetryPort.noOp());
    }

    /** 注入来源事实、索引能力、时间和低基数重建遥测。 */
    public KnowledgeIndexRebuildUseCase(ManagedDocumentRepository documents,
                                        ResolvedCaseRepository cases,
                                        KnowledgeSpaceRepository spaces,
                                        DocumentChunker chunker,
                                        EmbeddingModelPort embeddings,
                                        KnowledgeIndexRebuildPort index,
                                        TimeProvider time,
                                        KnowledgeSpaceTelemetryPort telemetry) {
        this.documents = documents;
        this.cases = cases;
        this.spaces = spaces;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.index = index;
        this.time = time;
        this.telemetry = telemetry == null ? KnowledgeSpaceTelemetryPort.noOp() : telemetry;
    }

    /** 仅允许平台管理员执行同步受控重建；任何核对失败都不会切换别名。 */
    public synchronized KnowledgeIndexRebuildResult rebuild(AuthenticatedUser actor) {
        if (actor == null) {
            throw new ApplicationException(ErrorCode.AUTH_UNAUTHORIZED, "认证信息无效或已经过期");
        }
        if (!actor.administrator()) {
            throw new ApplicationException(ErrorCode.AUTH_FORBIDDEN, "仅平台管理员可以重建知识索引");
        }
        try {
            Set<UUID> knownSpaces = knownSpaceIds();
            index.prepareTarget();
            RebuildCount documentCount = rebuildDocuments(knownSpaces);
            RebuildCount caseCount = rebuildCases(knownSpaces);
            long expectedSources = documents.count(ManagedDocumentStatus.PUBLISHED, null,
                    Set.of(), true) + cases.count(ResolvedCaseStatus.PUBLISHED, null, null,
                    Set.of(), true);
            long actualChunks = index.countTargetChunks();
            long expectedChunks = documentCount.chunkCount() + caseCount.chunkCount();
            if (documentCount.sourceCount() + caseCount.sourceCount() != expectedSources
                    || actualChunks != expectedChunks
                    || !index.targetSpacesAreKnown(knownSpaces)) {
                throw new IllegalStateException("知识索引全量重建完整性校验失败");
            }
            index.activateTarget();
            telemetry.recordRebuild(KnowledgeSpaceTelemetryPort.RebuildResult.SUCCEEDED);
            return new KnowledgeIndexRebuildResult(expectedSources, documentCount.sourceCount(),
                    caseCount.sourceCount(), actualChunks, true);
        } catch (RuntimeException exception) {
            telemetry.recordRebuild(KnowledgeSpaceTelemetryPort.RebuildResult.FAILED);
            throw exception;
        }
    }

    /** 分页重建全部当前已发布托管文档并逐来源校验版本、空间和哈希。 */
    private RebuildCount rebuildDocuments(Set<UUID> knownSpaces) {
        long count = 0;
        long chunkCount = 0;
        int offset = 0;
        while (true) {
            List<ManagedDocument> batch = documents.findPage(ManagedDocumentStatus.PUBLISHED,
                    null, Set.of(), true, offset, PAGE_SIZE);
            for (ManagedDocument document : batch) {
                requireKnownSpace(document.spaceId(), knownSpaces);
                List<IndexedKnowledgeChunk> chunks = chunks("MANAGED_DOCUMENT", document.id(),
                        document.version(), document.spaceId(), document.title(),
                        document.inputType(), document.rawContent(), document.publishedAt());
                writeAndVerify("MANAGED_DOCUMENT", document.id(), document.version(),
                        document.spaceId(), chunks);
                count++;
                chunkCount += chunks.size();
            }
            if (batch.size() < PAGE_SIZE) return new RebuildCount(count, chunkCount);
            offset += batch.size();
        }
    }

    /** 分页重建全部当前已发布案例并逐来源校验版本、空间和哈希。 */
    private RebuildCount rebuildCases(Set<UUID> knownSpaces) {
        long count = 0;
        long chunkCount = 0;
        int offset = 0;
        while (true) {
            List<ResolvedCase> batch = cases.findPage(ResolvedCaseStatus.PUBLISHED, null,
                    null, Set.of(), true, offset, PAGE_SIZE);
            for (ResolvedCase value : batch) {
                requireKnownSpace(value.spaceId(), knownSpaces);
                String content = "# " + value.title() + "\n\n## 问题\n" + value.problem()
                        + "\n\n## 根因\n" + value.cause() + "\n\n## 解决方案\n" + value.solution();
                List<IndexedKnowledgeChunk> chunks = chunks("RESOLVED_CASE", value.id(),
                        value.version(), value.spaceId(), value.title(),
                        DocumentInputType.DIRECT_TEXT, content, value.publishedAt());
                writeAndVerify("RESOLVED_CASE", value.id(), value.version(),
                        value.spaceId(), chunks);
                count++;
                chunkCount += chunks.size();
            }
            if (batch.size() < PAGE_SIZE) return new RebuildCount(count, chunkCount);
            offset += batch.size();
        }
    }

    /** 复用生产分块与 Embedding 规则创建一个来源的完整目标分块。 */
    private List<IndexedKnowledgeChunk> chunks(String sourceType, long sourceId, long version,
                                                UUID spaceId, String title,
                                                DocumentInputType inputType, String content,
                                                Instant publishedAt) {
        List<KnowledgeChunkDraft> drafts = chunker.chunk(title, inputType, content);
        List<List<Double>> vectors = embeddings.embedDocuments(
                drafts.stream().map(KnowledgeChunkDraft::content).toList(), () -> { });
        if (drafts.isEmpty() || vectors == null || vectors.size() != drafts.size()) {
            throw new IllegalStateException("知识索引重建向量数量不完整");
        }
        List<IndexedKnowledgeChunk> result = new ArrayList<>();
        Instant indexedAt = time.now();
        for (int position = 0; position < drafts.size(); position++) {
            List<Double> vector = vectors.get(position);
            if (vector == null || vector.size() != 1024 || vector.stream().anyMatch(
                    value -> value == null || !Double.isFinite(value))) {
                throw new IllegalStateException("知识索引重建向量不合法");
            }
            KnowledgeChunkDraft draft = drafts.get(position);
            result.add(new IndexedKnowledgeChunk(sourceType + ":" + sourceId + ":" + version
                    + ":" + draft.chunkIndex(), sourceType, sourceId, version, spaceId,
                    draft.chunkIndex(), title, draft.headingPath(), draft.content(),
                    draft.exactTerms(), draft.contentHash(), vector, publishedAt, indexedAt,
                    DocumentChunker.VERSION, ExactTermExtractor.VERSION));
        }
        return List.copyOf(result);
    }

    /** 写入目标索引并校验当前来源的空间、版本、分块数量和内容哈希。 */
    private void writeAndVerify(String sourceType, long sourceId, long version, UUID spaceId,
                                List<IndexedKnowledgeChunk> chunks) {
        index.indexTargetChunks(chunks);
        if (!index.verifyTargetVersion(sourceType, sourceId, version, spaceId,
                chunks.stream().map(IndexedKnowledgeChunk::contentHash).toList())) {
            throw new IllegalStateException("知识索引重建来源完整性校验失败");
        }
    }

    /** 读取全部已知空间 UUID，重建不按状态排除已发布历史事实。 */
    private Set<UUID> knownSpaceIds() {
        Set<UUID> result = new HashSet<>();
        int offset = 0;
        while (true) {
            List<KnowledgeSpace> batch = spaces.findAdminPage(null, null, null, offset, PAGE_SIZE);
            batch.forEach(value -> result.add(value.spaceId()));
            if (batch.size() < PAGE_SIZE) return Set.copyOf(result);
            offset += batch.size();
        }
    }

    /** 拒绝来源引用不存在的空间，防止未知空间分块进入新索引。 */
    private void requireKnownSpace(UUID spaceId, Set<UUID> knownSpaces) {
        if (spaceId == null || !knownSpaces.contains(spaceId)) {
            throw new IllegalStateException("已发布知识来源缺少有效空间");
        }
    }

    /** 保存一类来源的数量和严格预期分块数。 */
    private record RebuildCount(long sourceCount, long chunkCount) { }
}
