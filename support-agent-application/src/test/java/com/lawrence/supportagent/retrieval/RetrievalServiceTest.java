package com.lawrence.supportagent.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lawrence.supportagent.model.EmbeddingModelPort;
import com.lawrence.supportagent.model.RerankModelPort;
import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpace;
import com.lawrence.supportagent.user.UserRole;
import com.lawrence.supportagent.retrieval.port.KnowledgeSearchPort;
import com.lawrence.supportagent.retrieval.port.KnowledgeSourceValidityPort;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 验证双路召回、RRF、来源回查、重排和降级三态。 */
class RetrievalServiceTest {
    /** 检索上下文不得携带 GLOBAL 与活动空间之外的第三个空间。 */
    @Test
    void shouldRejectAccessContextWithAdditionalSpace() {
        UUID activeSpaceId = UUID.randomUUID();
        AuthenticatedUser actor = new AuthenticatedUser(UUID.randomUUID(), "test", UserRole.USER);

        assertThatThrownBy(() -> new RetrievalAccessContext(actor, activeSpaceId,
                Set.of(KnowledgeSpace.GLOBAL_SPACE_ID, activeSpaceId, UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("检索允许空间必须严格等于 GLOBAL 加活动空间");
    }

    /** BM25、向量和 MySQL 回查必须接收完全相同的最小允许空间集合。 */
    @Test
    void shouldUseSameMinimalSpaceSetForBothBranchesAndValidityCheck() {
        UUID activeSpaceId = UUID.randomUUID();
        Set<UUID> expected = Set.of(KnowledgeSpace.GLOBAL_SPACE_ID, activeSpaceId);
        AtomicReference<Set<UUID>> bm25Spaces = new AtomicReference<>();
        AtomicReference<Set<UUID>> vectorSpaces = new AtomicReference<>();
        AtomicReference<Set<UUID>> validitySpaces = new AtomicReference<>();
        RetrievalEvidence allowed = new RetrievalEvidence("allowed", "MANAGED_DOCUMENT", 1, 2,
                activeSpaceId, "标题", "章节", "连接失败排查", List.of(), Set.of(),
                null, null, 0, null);
        KnowledgeSearchPort search = new KnowledgeSearchPort() {
            /** {@inheritDoc} */
            public List<RetrievalEvidence> searchBm25(String query, Set<UUID> spaces, int limit) {
                bm25Spaces.set(spaces);
                return List.of(allowed);
            }

            /** {@inheritDoc} */
            public List<RetrievalEvidence> searchVector(List<Double> vector, Set<UUID> spaces,
                                                         int limit, int candidates,
                                                         double similarity) {
                vectorSpaces.set(spaces);
                return List.of(allowed);
            }
        };
        KnowledgeSourceValidityPort validity = (sources, spaces) -> {
            validitySpaces.set(spaces);
            return Set.copyOf(sources);
        };
        AuthenticatedUser actor = new AuthenticatedUser(UUID.randomUUID(), "test", UserRole.USER);
        try (RetrievalService service = new RetrievalService(search, validity, embedding(),
                (query, documents) -> List.of(), parameters(0.50))) {
            service.rank("连接失败", RetrievalMode.HYBRID,
                    new RetrievalAccessContext(actor, activeSpaceId, expected));
        }

        assertThat(bm25Spaces.get()).isEqualTo(expected);
        assertThat(vectorSpaces.get()).isEqualTo(expected);
        assertThat(validitySpaces.get()).isEqualTo(expected);
    }

    /** 双路命中且重排超过阈值时返回可靠知识。 */
    @Test
    void shouldReturnGroundedEvidenceAfterHybridFusion() {
        RetrievalEvidence candidate = evidence("chunk-1", Set.of("title_or_heading"));
        KnowledgeSearchPort search = search(List.of(candidate), List.of(candidate));
        KnowledgeSourceValidityPort validity = (sources, allowed) -> Set.copyOf(sources);
        EmbeddingModelPort embedding = embedding();
        RerankModelPort rerank = (query, documents) -> documents.stream()
                .map(item -> new RerankModelPort.RerankScore(item.chunkId(), 0.91)).toList();
        try (RetrievalService service = new RetrievalService(search, validity, embedding,
                rerank, parameters(0.50))) {
            RetrievalResult result = service.retrieve("连接失败", context());
            assertThat(result.status()).isEqualTo(RetrievalStatus.GROUNDED);
            assertThat(result.evidence()).extracting(RetrievalEvidence::chunkId).containsExactly("chunk-1");
        }
    }

    /** 原始排名存在候选时，可靠性门槛仍必须能把最终状态判为无知识。 */
    @Test
    void shouldKeepRawRankingWhenGroundedThresholdRejectsCandidate() {
        RetrievalEvidence candidate = evidence("chunk-1", Set.of("content"));
        RerankModelPort rerank = (query, documents) -> List.of(
                new RerankModelPort.RerankScore("chunk-1", 0.20));
        try (RetrievalService service = new RetrievalService(
                search(List.of(candidate), List.of(candidate)),
                (sources, allowed) -> Set.copyOf(sources), embedding(), rerank, parameters(0.50))) {
            RetrievalRanking ranking = service.rank("不相关问题", RetrievalMode.HYBRID_RERANK);

            assertThat(ranking.candidates()).extracting(RetrievalEvidence::chunkId)
                    .containsExactly("chunk-1");
            assertThat(ranking.reliableEvidence()).isEmpty();
            assertThat(ranking.status()).isEqualTo(RetrievalStatus.NO_RELIABLE_KNOWLEDGE);
            assertThat(ranking.decisionReason())
                    .isEqualTo(RetrievalDecisionReason.BELOW_GROUNDED_THRESHOLD);
        }
    }

    /** 两个召回分支均异常时必须明确返回检索失败而不是无知识。 */
    @Test
    void shouldDistinguishTechnicalFailureFromNoKnowledge() {
        KnowledgeSearchPort search = searchFailure();
        try (RetrievalService service = new RetrievalService(search, (sources, allowed) -> Set.of(), embeddingFailure(),
                (query, documents) -> List.of(), parameters(0.50))) {
            RetrievalResult result = service.retrieve("连接失败", context());
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
        try (RetrievalService service = new RetrievalService(search, (sources, allowed) -> Set.copyOf(sources),
                embedding(), (query, documents) -> List.of(), parameters(0.50))) {
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
        try (RetrievalService service = new RetrievalService(search, (sources, allowed) -> Set.copyOf(sources),
                embedding(), (query, documents) -> List.of(), parameters(0.50))) {
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
            /** {@inheritDoc} */ public List<RetrievalEvidence> searchBm25(String query, Set<UUID> spaces, int limit) { return bm25; }
            /** {@inheritDoc} */ public List<RetrievalEvidence> searchVector(List<Double> value, Set<UUID> spaces, int limit,
                    int candidates, double similarity) { return vector; }
        };
    }
    /** 创建两个分支均失败的搜索端口。 */
    private KnowledgeSearchPort searchFailure() {
        return new KnowledgeSearchPort() {
            /** {@inheritDoc} */ public List<RetrievalEvidence> searchBm25(String query, Set<UUID> spaces, int limit) { throw new IllegalStateException(); }
            /** {@inheritDoc} */ public List<RetrievalEvidence> searchVector(List<Double> value, Set<UUID> spaces, int limit,
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

    /** 创建只覆盖可靠性门槛、其余保持阶段七冻结值的检索参数。 */
    private RetrievalParameters parameters(double threshold) {
        return RetrievalParameters.baseline().withGroundedThreshold(threshold);
    }

    /** 创建只允许 GLOBAL 的显式锁定评测访问上下文。 */
    private RetrievalAccessContext context() {
        AuthenticatedUser actor = new AuthenticatedUser(UUID.randomUUID(), "test", UserRole.ADMIN);
        return new RetrievalAccessContext(actor, KnowledgeSpace.GLOBAL_SPACE_ID,
                Set.of(KnowledgeSpace.GLOBAL_SPACE_ID));
    }
}
