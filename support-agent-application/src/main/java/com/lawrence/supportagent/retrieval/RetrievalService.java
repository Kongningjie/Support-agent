package com.lawrence.supportagent.retrieval;

import com.lawrence.supportagent.model.EmbeddingModelPort;
import com.lawrence.supportagent.model.RerankModelPort;
import com.lawrence.supportagent.model.RerankModelPort.RerankDocument;
import com.lawrence.supportagent.model.RerankModelPort.RerankScore;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort.Operation;
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
    private final KnowledgeSearchPort searchPort;
    private final KnowledgeSourceValidityPort validityPort;
    private final EmbeddingModelPort embeddingModel;
    private final RerankModelPort rerankModel;
    private final RetrievalParameters parameters;
    private final OptimizationTelemetryPort telemetry;
    private final ExecutorService branches = Executors.newVirtualThreadPerTaskExecutor();

    /** 创建使用显式参数快照且不执行在线调优的混合检索服务。 */
    public RetrievalService(KnowledgeSearchPort searchPort, KnowledgeSourceValidityPort validityPort,
                            EmbeddingModelPort embeddingModel, RerankModelPort rerankModel,
                            RetrievalParameters parameters) {
        this(searchPort, validityPort, embeddingModel, rerankModel, parameters,
                OptimizationTelemetryPort.noOp());
    }

    /** 创建带二期低基数遥测的混合检索服务。 */
    public RetrievalService(KnowledgeSearchPort searchPort, KnowledgeSourceValidityPort validityPort,
                            EmbeddingModelPort embeddingModel, RerankModelPort rerankModel,
                            RetrievalParameters parameters,
                            OptimizationTelemetryPort telemetry) {
        this.searchPort = searchPort;
        this.validityPort = validityPort;
        this.embeddingModel = embeddingModel;
        this.rerankModel = rerankModel;
        this.parameters = java.util.Objects.requireNonNull(parameters, "检索参数不能为空");
        this.telemetry = telemetry == null ? OptimizationTelemetryPort.noOp() : telemetry;
    }

    /** 执行完整检索并保证单分支失败可降级、双分支失败明确报错。 */
    public RetrievalResult retrieve(String query) {
        long started = System.nanoTime();
        boolean succeeded = false;
        try {
            CompletableFuture<BranchResult> bm25Task = CompletableFuture.supplyAsync(
                    () -> callBm25(query), branches);
            CompletableFuture<BranchResult> vectorTask = CompletableFuture.supplyAsync(
                    () -> callVector(query), branches);
            BranchResult bm25 = bm25Task.join();
            BranchResult vector = vectorTask.join();
            if (bm25.status == BranchStatus.FAILED && vector.status == BranchStatus.FAILED) {
                return failed(started);
            }
            List<RetrievalEvidence> fused = validSources(fuse(bm25.items, vector.items));
            RerankOutcome reranked = rerank(query, fused);
            List<RetrievalEvidence> selected = selectReliable(reranked.items, reranked.status);
            RetrievalStatus status = selected.isEmpty() ? RetrievalStatus.NO_RELIABLE_KNOWLEDGE
                    : RetrievalStatus.GROUNDED;
            RetrievalResult result = new RetrievalResult(status, bm25.status, vector.status,
                    reranked.status, selected, fused, elapsed(started));
            succeeded = true;
            return result;
        } finally {
            telemetry.recordDuration(Operation.RETRIEVAL, elapsed(started), succeeded);
        }
    }

    /** 按固定评测模式同时返回原始排名和应用生产可靠性规则后的最终判断。 */
    public RetrievalRanking rank(String query, RetrievalMode mode) {
        if (query == null || query.isBlank() || mode == null) {
            throw new IllegalArgumentException("评测问题和检索模式不能为空");
        }
        BranchResult bm25 = mode == RetrievalMode.VECTOR_ONLY
                ? new BranchResult(BranchStatus.SKIPPED, List.of()) : callBm25(query);
        BranchResult vector = mode == RetrievalMode.BM25_ONLY
                ? new BranchResult(BranchStatus.SKIPPED, List.of()) : callVector(query);
        List<RetrievalEvidence> candidates = switch (mode) {
            case BM25_ONLY -> validSources(bm25.items);
            case VECTOR_ONLY -> validSources(vector.items);
            case HYBRID, HYBRID_RERANK -> validSources(fuse(bm25.items, vector.items));
        };
        RerankOutcome reranked = mode == RetrievalMode.HYBRID_RERANK
                ? rerank(query, candidates)
                : new RerankOutcome(BranchStatus.SKIPPED, candidates);
        boolean failed = requiredBranchesFailed(mode, bm25.status, vector.status);
        List<RetrievalEvidence> reliable = failed ? List.of()
                : selectReliable(reranked.items, reranked.status);
        RetrievalStatus status = failed ? RetrievalStatus.RETRIEVAL_FAILED
                : reliable.isEmpty() ? RetrievalStatus.NO_RELIABLE_KNOWLEDGE
                : RetrievalStatus.GROUNDED;
        return new RetrievalRanking(mode,
                reranked.items.stream().limit(parameters.rankedTopK()).toList(),
                reliable, status, decisionReason(status, reranked),
                bm25.status, vector.status, reranked.status);
    }

    /** 安全执行 BM25 分支并把异常收敛为分支失败。 */
    private BranchResult callBm25(String query) {
        try {
            return new BranchResult(BranchStatus.SUCCEEDED,
                    ranked(searchPort.searchBm25(query, parameters.bm25TopK()), true,
                            parameters.bm25TopK()));
        } catch (RuntimeException exception) {
            return new BranchResult(BranchStatus.FAILED, List.of());
        }
    }

    /** 安全执行向量分支并把模型或搜索异常收敛为分支失败。 */
    private BranchResult callVector(String query) {
        try {
            List<Double> vector = embeddingModel.embedQuery(query);
            return new BranchResult(BranchStatus.SUCCEEDED, ranked(searchPort.searchVector(vector,
                    parameters.vectorTopK(), parameters.vectorCandidates(),
                    parameters.vectorMinimumSimilarity()), false, parameters.vectorTopK()));
        } catch (RuntimeException exception) {
            return new BranchResult(BranchStatus.FAILED, List.of());
        }
    }

    /** 为分支结果写入从一开始的稳定排名。 */
    private List<RetrievalEvidence> ranked(List<RetrievalEvidence> items, boolean bm25, int limit) {
        if (items == null) {
            return List.of();
        }
        List<RetrievalEvidence> result = new ArrayList<>();
        for (int index = 0; index < Math.min(limit, items.size()); index++) {
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
                .sorted(rrfOrder()).limit(parameters.fusionTopK()).toList();
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
            List<RetrievalEvidence> rerankCandidates = candidates.stream()
                    .limit(parameters.rerankTopK()).toList();
            List<RerankDocument> documents = rerankCandidates.stream().map(value ->
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
            if (byId.size() != rerankCandidates.size()
                    || !byId.keySet().equals(rerankCandidates.stream().map(
                    RetrievalEvidence::chunkId).collect(java.util.stream.Collectors.toSet()))) {
                throw new IllegalArgumentException("Rerank 返回 ID 不完整");
            }
            List<RetrievalEvidence> sorted = rerankCandidates.stream().map(value -> copy(value,
                            value.bm25Rank(), value.vectorRank(), value.rrfScore(), byId.get(value.chunkId())))
                    .sorted(Comparator.comparing(RetrievalEvidence::rerankScore).reversed()
                            .thenComparingInt(this::sourcePriority)
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
                    ? item.rerankScore() >= parameters.groundedThreshold() : degradedReliable(item);
            String source = item.sourceType() + ":" + item.sourceId();
            if (reliable && perSource.getOrDefault(source, 0) < 2) {
                selected.add(item);
                perSource.merge(source, 1, Integer::sum);
            }
            if (selected.size() == parameters.finalTopK()) {
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
        if (value.bm25Rank() != null) score += 1.0 / (parameters.rrfK() + value.bm25Rank());
        if (value.vectorRank() != null) score += 1.0 / (parameters.rrfK() + value.vectorRank());
        return score;
    }

    /** 判断当前评测模式是否丢失了得出结果所需的全部召回分支。 */
    private boolean requiredBranchesFailed(RetrievalMode mode, BranchStatus bm25,
                                           BranchStatus vector) {
        return switch (mode) {
            case BM25_ONLY -> bm25 == BranchStatus.FAILED;
            case VECTOR_ONLY -> vector == BranchStatus.FAILED;
            case HYBRID, HYBRID_RERANK -> bm25 == BranchStatus.FAILED
                    && vector == BranchStatus.FAILED;
        };
    }

    /** 把最终状态转换为不会依赖正文的稳定诊断原因。 */
    private RetrievalDecisionReason decisionReason(RetrievalStatus status,
                                                   RerankOutcome reranked) {
        if (status == RetrievalStatus.RETRIEVAL_FAILED) {
            return RetrievalDecisionReason.RETRIEVAL_BRANCH_FAILED;
        }
        if (status == RetrievalStatus.GROUNDED) {
            return RetrievalDecisionReason.RELIABLE_EVIDENCE_PRESENT;
        }
        if (reranked.items.isEmpty()) {
            return RetrievalDecisionReason.NO_CANDIDATE;
        }
        return reranked.status == BranchStatus.SUCCEEDED
                ? RetrievalDecisionReason.BELOW_GROUNDED_THRESHOLD
                : RetrievalDecisionReason.INSUFFICIENT_DEGRADED_SIGNAL;
    }

    /** 返回 RRF 并列时仍可重复的稳定排序器。 */
    private Comparator<RetrievalEvidence> rrfOrder() {
        return Comparator.comparingDouble(RetrievalEvidence::rrfScore).reversed()
                .thenComparingInt(this::sourcePriority)
                .thenComparing(value -> value.bm25Rank() != null && value.vectorRank() != null ? 0 : 1)
                .thenComparingInt(value -> Math.min(value.bm25Rank() == null ? Integer.MAX_VALUE : value.bm25Rank(),
                        value.vectorRank() == null ? Integer.MAX_VALUE : value.vectorRank()))
                .thenComparingInt(value -> value.bm25Rank() == null ? Integer.MAX_VALUE : value.bm25Rank())
                .thenComparing(RetrievalEvidence::chunkId);
    }

    /** 在分数完全相同时优先选择人工维护的托管文档。 */
    private int sourcePriority(RetrievalEvidence value) {
        return "MANAGED_DOCUMENT".equals(value.sourceType()) ? 0 : 1;
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
