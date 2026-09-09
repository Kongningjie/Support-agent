package com.lawrence.supportagent.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.lawrence.supportagent.model.EmbeddingModelPort;
import com.lawrence.supportagent.model.RerankModelPort;
import com.lawrence.supportagent.retrieval.port.KnowledgeSearchPort;
import com.lawrence.supportagent.retrieval.port.KnowledgeSourceValidityPort;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 验证双路召回、RRF、来源回查、重排和降级三态。 */
class RetrievalServiceTest {
    /** 双路命中且重排超过阈值时返回可靠知识。 */
    @Test
    void shouldReturnGroundedEvidenceAfterHybridFusion() {
        RetrievalEvidence candidate = evidence("chunk-1", Set.of("title_or_heading"));
        KnowledgeSearchPort search = search(List.of(candidate), List.of(candidate));
        KnowledgeSourceValidityPort validity = sources -> Set.copyOf(sources);
        EmbeddingModelPort embedding = embedding();
        RerankModelPort rerank = (query, documents) -> documents.stream()
                .map(item -> new RerankModelPort.RerankScore(item.chunkId(), 0.91)).toList();
        try (RetrievalService service = new RetrievalService(search, validity, embedding,
                rerank, 0.20, 200, 0.50)) {
            RetrievalResult result = service.retrieve("连接失败");
            assertThat(result.status()).isEqualTo(RetrievalStatus.GROUNDED);
            assertThat(result.evidence()).extracting(RetrievalEvidence::chunkId).containsExactly("chunk-1");
        }
    }

    /** 两个召回分支均异常时必须明确返回检索失败而不是无知识。 */
    @Test
    void shouldDistinguishTechnicalFailureFromNoKnowledge() {
        KnowledgeSearchPort search = searchFailure();
        try (RetrievalService service = new RetrievalService(search, sources -> Set.of(), embeddingFailure(),
                (query, documents) -> List.of(), 0.20, 200, 0.50)) {
            RetrievalResult result = service.retrieve("连接失败");
            assertThat(result.status()).isEqualTo(RetrievalStatus.RETRIEVAL_FAILED);
            assertThat(result.rerankStatus()).isEqualTo(BranchStatus.SKIPPED);
        }
    }

    /** 验证高相关案例优先于低相关文档，避免来源类型覆盖检索分数。 */
    @Test
    void shouldRankByRelevanceBeforeSourceType() {
        RetrievalEvidence resolvedCase = evidence("case", "RESOLVED_CASE", 2);
        RetrievalEvidence managedDocument = evidence("document", "MANAGED_DOCUMENT", 1);
        KnowledgeSearchPort search = search(List.of(resolvedCase, managedDocument),
                List.of(resolvedCase, managedDocument));
        try (RetrievalService service = new RetrievalService(search, sources -> Set.copyOf(sources),
                embedding(), (query, documents) -> List.of(), 0.20, 200, 0.50)) {
            RetrievalRanking ranking = service.rank("连接失败", RetrievalMode.HYBRID);

            assertThat(ranking.candidates()).extracting(RetrievalEvidence::chunkId)
                    .containsExactly("case", "document");
        }
    }

    /** 验证融合分数相同时优先人工维护的托管文档。 */
    @Test
    void shouldPreferManagedDocumentWhenScoresTie() {
        RetrievalEvidence resolvedCase = evidence("case", "RESOLVED_CASE", 2);
        RetrievalEvidence managedDocument = evidence("document", "MANAGED_DOCUMENT", 1);
        KnowledgeSearchPort search = search(List.of(managedDocument, resolvedCase),
                List.of(resolvedCase, managedDocument));
        try (RetrievalService service = new RetrievalService(search, sources -> Set.copyOf(sources),
                embedding(), (query, documents) -> List.of(), 0.20, 200, 0.50)) {
            RetrievalRanking ranking = service.rank("连接失败", RetrievalMode.HYBRID);

            assertThat(ranking.candidates()).extracting(RetrievalEvidence::chunkId)
                    .containsExactly("document", "case");
        }
    }

    /** 创建无阶段分数的原始检索候选。 */
    private RetrievalEvidence evidence(String id, Set<String> matches) {
        return new RetrievalEvidence(id, "MANAGED_DOCUMENT", 1, 2, "标题", "章节",
                "连接失败排查", List.of(), matches, null, null, 0, null);
    }

    /** 创建指定来源类型的原始检索候选。 */
    private RetrievalEvidence evidence(String id, String sourceType, long sourceId) {
        return new RetrievalEvidence(id, sourceType, sourceId, 2, "标题", "章节",
                "连接失败排查", List.of(), Set.of(), null, null, 0, null);
    }
    /** 创建返回固定候选的搜索端口。 */
    private KnowledgeSearchPort search(List<RetrievalEvidence> bm25, List<RetrievalEvidence> vector) {
        return new KnowledgeSearchPort() {
            /** {@inheritDoc} */ public List<RetrievalEvidence> searchBm25(String query, int limit) { return bm25; }
            /** {@inheritDoc} */ public List<RetrievalEvidence> searchVector(List<Double> value, int limit,
                    int candidates, double similarity) { return vector; }
        };
    }
    /** 创建两个分支均失败的搜索端口。 */
    private KnowledgeSearchPort searchFailure() {
        return new KnowledgeSearchPort() {
            /** {@inheritDoc} */ public List<RetrievalEvidence> searchBm25(String query, int limit) { throw new IllegalStateException(); }
            /** {@inheritDoc} */ public List<RetrievalEvidence> searchVector(List<Double> value, int limit,
                    int candidates, double similarity) { throw new IllegalStateException(); }
        };
    }
    /** 创建固定查询向量端口。 */
    private EmbeddingModelPort embedding() {
        return new EmbeddingModelPort() {
            /** {@inheritDoc} */ public List<List<Double>> embedDocuments(List<String> values, Runnable callback) { return List.of(); }
            /** {@inheritDoc} */ public List<Double> embedQuery(String query) { return List.of(0.1); }
        };
    }
    /** 创建失败的查询向量端口。 */
    private EmbeddingModelPort embeddingFailure() {
        return new EmbeddingModelPort() {
            /** {@inheritDoc} */ public List<List<Double>> embedDocuments(List<String> values, Runnable callback) { return List.of(); }
            /** {@inheritDoc} */ public List<Double> embedQuery(String query) { throw new IllegalStateException(); }
        };
    }
}
