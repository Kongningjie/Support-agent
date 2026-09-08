package com.lawrence.supportagent.retrieval;

import com.lawrence.supportagent.model.EmbeddingModelPort;
import com.lawrence.supportagent.model.RerankModelPort;
import com.lawrence.supportagent.model.RerankModelPort.RerankDocument;
import com.lawrence.supportagent.model.RerankModelPort.RerankScore;
import com.lawrence.supportagent.retrieval.port.KnowledgeSearchPort;
import com.lawrence.supportagent.retrieval.port.KnowledgeSourceValidityPort;
import com.lawrence.supportagent.retrieval.port.KnowledgeSourceValidityPort.SourceVersion;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 并行执行双路召回、RRF、来源回查、Rerank 和可靠知识门槛。 */
public class RetrievalService implements AutoCloseable {
    private static final int BRANCH_LIMIT = 50;
    private static final int FUSION_LIMIT = 30;
    private static final int EVIDENCE_LIMIT = 5;
    private static final int RRF_K = 60;
    private final KnowledgeSearchPort searchPort;
    private final KnowledgeSourceValidityPort validityPort;
    private final EmbeddingModelPort embeddingModel;
    private final RerankModelPort rerankModel;
    private final double vectorMinimumSimilarity;
    private final int vectorCandidates;
    private final double groundedThreshold;
    private final ExecutorService branches = Executors.newVirtualThreadPerTaskExecutor();

    /** 创建参数固定且不执行在线调优的混合检索服务。 */
    public RetrievalService(KnowledgeSearchPort searchPort, KnowledgeSourceValidityPort validityPort,
                            EmbeddingModelPort embeddingModel, RerankModelPort rerankModel,
                            double vectorMinimumSimilarity, int vectorCandidates,
                            double groundedThreshold) {
        if (!Double.isFinite(vectorMinimumSimilarity) || vectorMinimumSimilarity < -1
                || vectorMinimumSimilarity > 1 || vectorCandidates < BRANCH_LIMIT
                || !Double.isFinite(groundedThreshold) || groundedThreshold < 0
                || groundedThreshold > 1) {
            throw new IllegalArgumentException("混合检索参数超出允许范围");
        }
        this.searchPort = searchPort;
        this.validityPort = validityPort;
        this.embeddingModel = embeddingModel;
        this.rerankModel = rerankModel;
        this.vectorMinimumSimilarity = vectorMinimumSimilarity;
        this.vectorCandidates = vectorCandidates;
        this.groundedThreshold = groundedThreshold;
    }

    /** 执行完整检索并保证单分支失败可降级、双分支失败明确报错。 */
    public RetrievalResult retrieve(String query) {
        long started = System.nanoTime();
        BranchResult bm25;
        BranchResult vector;
        CompletableFuture<BranchResult> bm25Task = CompletableFuture.supplyAsync(
                () -> callBm25(query), branches);
        CompletableFuture<BranchResult> vectorTask = CompletableFuture.supplyAsync(
                () -> callVector(query), branches);
        bm25 = bm25Task.join();
        vector = vectorTask.join();
        if (bm25.status == BranchStatus.FAILED && vector.status == BranchStatus.FAILED) {
            return failed(started);
        }
        List<RetrievalEvidence> fused = validSources(fuse(bm25.items, vector.items));
        RerankOutcome reranked = rerank(query, fused);
        List<RetrievalEvidence> selected = selectReliable(reranked.items, reranked.status);
        RetrievalStatus status = selected.isEmpty() ? RetrievalStatus.NO_RELIABLE_KNOWLEDGE
                : RetrievalStatus.GROUNDED;
        return new RetrievalResult(status, bm25.status, vector.status, reranked.status,
                selected, fused, elapsed(started));
    }

    /** 安全执行 BM25 分支并把异常收敛为分支失败。 */
    private BranchResult callBm25(String query) {
        try {
            return new BranchResult(BranchStatus.SUCCEEDED,
                    ranked(searchPort.searchBm25(query, BRANCH_LIMIT), true));
        } catch (RuntimeException exception) {
            return new BranchResult(BranchStatus.FAILED, List.of());
        }
    }

    /** 安全执行向量分支并把模型或搜索异常收敛为分支失败。 */
    private BranchResult callVector(String query) {
        try {
            List<Double> vector = embeddingModel.embedQuery(query);
            return new BranchResult(BranchStatus.SUCCEEDED, ranked(searchPort.searchVector(vector,
                    BRANCH_LIMIT, vectorCandidates, vectorMinimumSimilarity), false));
        } catch (RuntimeException exception) {
            return new BranchResult(BranchStatus.FAILED, List.of());
        }
    }

    /** 为分支结果写入从一开始的稳定排名。 */
    private List<RetrievalEvidence> ranked(List<RetrievalEvidence> items, boolean bm25) {
        if (items == null) {
            return List.of();
        }
        List<RetrievalEvidence> result = new ArrayList<>();
        for (int index = 0; index < Math.min(BRANCH_LIMIT, items.size()); index++) {
            RetrievalEvidence value = items.get(index);
            result.add(copy(value, bm25 ? index + 1 : null, bm25 ? null : index + 1, 0, null));
        }
        return result;
    }

    /** 使用固定 RRF 公式融合并执行确定性并列排序。 */
    private List<RetrievalEvidence> fuse(List<RetrievalEvidence> bm25, List<RetrievalEvidence> vector) {
        Map<String, RetrievalEvidence> values = new LinkedHashMap<>();
        bm25.forEach(value -> values.put(value.chunkId(), value));
        for (RetrievalEvidence value : vector) {
            RetrievalEvidence old = values.get(value.chunkId());
            values.put(value.chunkId(), old == null ? value : merge(old, value));
        }
        return values.values().stream().map(value -> copy(value, value.bm25Rank(),
                        value.vectorRank(), rrf(value), null))
                .sorted(rrfOrder()).limit(FUSION_LIMIT).toList();
    }

    /** 合并同一分块的两个分支排名和命中字段。 */
    private RetrievalEvidence merge(RetrievalEvidence left, RetrievalEvidence right) {
        Set<String> matches = new HashSet<>(left.matchedQueries());
        matches.addAll(right.matchedQueries());
        return new RetrievalEvidence(left.chunkId(), left.sourceType(), left.sourceId(),
                left.sourceVersion(), left.title(), left.headingPath(), left.content(),
                left.exactTerms(), Set.copyOf(matches), left.bm25Rank(), right.vectorRank(), 0, null);
    }

    /** 批量剔除 MySQL 中已归档、删除或版本失效的索引候选。 */
    private List<RetrievalEvidence> validSources(List<RetrievalEvidence> candidates) {
        List<SourceVersion> requested = candidates.stream().map(value -> new SourceVersion(
                value.sourceType(), value.sourceId(), value.sourceVersion())).distinct().toList();
        Set<SourceVersion> valid = validityPort.findValid(requested);
        return candidates.stream().filter(value -> valid.contains(new SourceVersion(
                value.sourceType(), value.sourceId(), value.sourceVersion()))).toList();
    }

    /** 调用重排端口；任何未知、重复、缺失 ID 或非法分数使整次重排降级。 */
    private RerankOutcome rerank(String query, List<RetrievalEvidence> candidates) {
        if (candidates.isEmpty()) {
            return new RerankOutcome(BranchStatus.SKIPPED, candidates);
        }
        try {
            List<RerankDocument> documents = candidates.stream().map(value ->
                    new RerankDocument(value.chunkId(), value.title() + "\n" + value.headingPath()
                            + "\n" + value.content())).toList();
            List<RerankScore> scores = rerankModel.rerank(query, documents);
            Map<String, Double> byId = new HashMap<>();
            for (RerankScore score : scores) {
                if (score == null || score.chunkId() == null || !Double.isFinite(score.score())
                        || score.score() < 0 || score.score() > 1
                        || byId.put(score.chunkId(), score.score()) != null) {
                    throw new IllegalArgumentException("Rerank 返回结构不合法");
                }
            }
            if (byId.size() != candidates.size()
                    || !byId.keySet().equals(candidates.stream().map(
                    RetrievalEvidence::chunkId).collect(java.util.stream.Collectors.toSet()))) {
                throw new IllegalArgumentException("Rerank 返回 ID 不完整");
            }
            List<RetrievalEvidence> sorted = candidates.stream().map(value -> copy(value,
                            value.bm25Rank(), value.vectorRank(), value.rrfScore(), byId.get(value.chunkId())))
                    .sorted(Comparator.comparing(RetrievalEvidence::rerankScore).reversed()
                            .thenComparing(rrfOrder())).toList();
            return new RerankOutcome(BranchStatus.SUCCEEDED, sorted);
        } catch (RuntimeException exception) {
            return new RerankOutcome(BranchStatus.DEGRADED, candidates);
        }
    }

    /** 应用正常阈值或降级保守门槛，并限制同来源最多两个分块。 */
    private List<RetrievalEvidence> selectReliable(List<RetrievalEvidence> items, BranchStatus status) {
        Map<String, Integer> perSource = new HashMap<>();
        List<RetrievalEvidence> selected = new ArrayList<>();
        for (RetrievalEvidence item : items) {
            boolean reliable = status == BranchStatus.SUCCEEDED
                    ? item.rerankScore() >= groundedThreshold : degradedReliable(item);
            String source = item.sourceType() + ":" + item.sourceId();
            if (reliable && perSource.getOrDefault(source, 0) < 2) {
                selected.add(item);
                perSource.merge(source, 1, Integer::sum);
            }
            if (selected.size() == EVIDENCE_LIMIT) {
                break;
            }
        }
        return List.copyOf(selected);
    }

    /** 判断候选是否满足 Rerank 不可用时的三条保守规则之一。 */
    private boolean degradedReliable(RetrievalEvidence item) {
        return item.bm25Rank() != null && item.vectorRank() != null
                || item.matchedQueries().contains("exact_term")
                || item.matchedQueries().contains("title_or_heading")
                && item.matchedQueries().contains("content");
    }

    /** 计算单个候选的固定 RRF 分数。 */
    private double rrf(RetrievalEvidence value) {
        double score = 0;
        if (value.bm25Rank() != null) score += 1.0 / (RRF_K + value.bm25Rank());
        if (value.vectorRank() != null) score += 1.0 / (RRF_K + value.vectorRank());
        return score;
    }

    /** 返回 RRF 并列时仍可重复的稳定排序器。 */
    private Comparator<RetrievalEvidence> rrfOrder() {
        return Comparator.comparingDouble(RetrievalEvidence::rrfScore).reversed()
                .thenComparing(value -> value.bm25Rank() != null && value.vectorRank() != null ? 0 : 1)
                .thenComparingInt(value -> Math.min(value.bm25Rank() == null ? Integer.MAX_VALUE : value.bm25Rank(),
                        value.vectorRank() == null ? Integer.MAX_VALUE : value.vectorRank()))
                .thenComparingInt(value -> value.bm25Rank() == null ? Integer.MAX_VALUE : value.bm25Rank())
                .thenComparing(RetrievalEvidence::chunkId);
    }

    /** 复制候选并替换检索阶段分数。 */
    private RetrievalEvidence copy(RetrievalEvidence value, Integer bm25Rank, Integer vectorRank,
                                   double rrfScore, Double rerankScore) {
        return new RetrievalEvidence(value.chunkId(), value.sourceType(), value.sourceId(),
                value.sourceVersion(), value.title(), value.headingPath(), value.content(),
                value.exactTerms(), value.matchedQueries(), bm25Rank, vectorRank, rrfScore, rerankScore);
    }

    /** 创建双分支失败结果。 */
    private RetrievalResult failed(long started) {
        return new RetrievalResult(RetrievalStatus.RETRIEVAL_FAILED, BranchStatus.FAILED,
                BranchStatus.FAILED, BranchStatus.SKIPPED, List.of(), List.of(), elapsed(started));
    }

    /** 将纳秒计时转换为非负毫秒。 */
    private long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    /** 关闭并行召回使用的虚拟线程执行器。 */
    @Override
    public void close() {
        branches.close();
    }

    /** 保存不会逃逸到公共契约的单分支结果。 */
    private record BranchResult(BranchStatus status, List<RetrievalEvidence> items) { }

    /** 保存重排成功或降级后的内部结果。 */
    private record RerankOutcome(BranchStatus status, List<RetrievalEvidence> items) { }
}
