package com.lawrence.supportagent.evaluation;

import com.lawrence.supportagent.retrieval.BranchStatus;
import com.lawrence.supportagent.retrieval.RetrievalEvidence;
import com.lawrence.supportagent.retrieval.RetrievalMode;
import com.lawrence.supportagent.retrieval.RetrievalRanking;
import com.lawrence.supportagent.retrieval.RetrievalService;
import com.lawrence.supportagent.retrieval.RetrievalStatus;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.sharedkernel.port.UuidGenerator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 在后台运行固定检索评测并仅在内存保存运行状态。 */
public class RetrievalEvaluationService implements AutoCloseable {
    private final RetrievalEvaluationDatasetPort dataset;
    private final RetrievalEvaluationReportPort reports;
    private final RetrievalService retrieval;
    private final RetrievalMetricsCalculator metrics;
    private final UuidGenerator ids;
    private final TimeProvider time;
    private final ConcurrentHashMap<UUID, RetrievalEvaluationRun> runs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** 注入固定数据集、报告、检索、指标、ID 和时间端口。 */
    public RetrievalEvaluationService(RetrievalEvaluationDatasetPort dataset,
                                      RetrievalEvaluationReportPort reports,
                                      RetrievalService retrieval,
                                      RetrievalMetricsCalculator metrics,
                                      UuidGenerator ids, TimeProvider time) {
        this.dataset = dataset;
        this.reports = reports;
        this.retrieval = retrieval;
        this.metrics = metrics;
        this.ids = ids;
        this.time = time;
    }

    /** 启动指定模式和可选用例子集的后台评测。 */
    public RetrievalEvaluationRun start(RetrievalMode mode, List<String> requestedCaseIds) {
        if (mode == null) throw new IllegalArgumentException("检索评测模式不能为空");
        List<RetrievalEvaluationCase> selected = select(dataset.load(), requestedCaseIds);
        UUID runId = ids.generate();
        RetrievalEvaluationRun pending = new RetrievalEvaluationRun(runId, mode,
                RetrievalEvaluationRun.Status.PENDING, 0, selected.size(), null, List.of(),
                null, time.now(), null);
        runs.put(runId, pending);
        executor.submit(() -> execute(runId, mode, selected));
        return pending;
    }

    /** 查询仍在当前进程内存中的评测运行。 */
    public RetrievalEvaluationRun get(UUID runId) {
        if (runId == null) throw new IllegalArgumentException("评测运行 ID 不能为空");
        RetrievalEvaluationRun run = runs.get(runId);
        if (run == null) throw new ApplicationException(ErrorCode.KNOWLEDGE_NOT_FOUND,
                "检索评测运行不存在或已经随应用重启清除");
        return run;
    }

    /** 顺序执行用例并持续更新进度，完成后生成只读报告。 */
    private void execute(UUID runId, RetrievalMode mode, List<RetrievalEvaluationCase> cases) {
        List<RetrievalEvaluationCaseResult> results = new ArrayList<>();
        update(runId, mode, RetrievalEvaluationRun.Status.RUNNING, 0, cases.size(),
                null, results, null, null);
        try {
            for (RetrievalEvaluationCase testCase : cases) {
                results.add(evaluate(testCase, retrieval.rank(testCase.query(), mode)));
                update(runId, mode, RetrievalEvaluationRun.Status.RUNNING, results.size(),
                        cases.size(), null, results, null, null);
            }
            RetrievalEvaluationMetrics calculated = metrics.calculate(cases, results);
            RetrievalEvaluationRun complete = update(runId, mode,
                    RetrievalEvaluationRun.Status.SUCCEEDED, results.size(), cases.size(),
                    calculated, results, null, time.now());
            reports.write(complete);
        } catch (RuntimeException exception) {
            String safe = "检索评测执行失败，请检查模型和检索依赖";
            update(runId, mode, RetrievalEvaluationRun.Status.FAILED, results.size(),
                    cases.size(), null, results, safe, time.now());
        }
    }

    /** 计算单条问题的状态、去重来源排名和精确词命中情况。 */
    private RetrievalEvaluationCaseResult evaluate(RetrievalEvaluationCase testCase,
                                                    RetrievalRanking ranking) {
        RetrievalStatus status = actualStatus(ranking);
        Set<String> sources = new LinkedHashSet<>();
        ranking.candidates().forEach(value -> sources.add(sourceId(value)));
        boolean exact = requiredTermsPresent(testCase.requiredExactTerms(), ranking.candidates());
        return new RetrievalEvaluationCaseResult(testCase.caseId(), testCase.expectedStatus(),
                status, List.copyOf(sources), exact, null);
    }

    /** 根据当前评测模式的必要分支状态推导三态结果。 */
    private RetrievalStatus actualStatus(RetrievalRanking ranking) {
        boolean failed = switch (ranking.mode()) {
            case BM25_ONLY -> ranking.bm25Status() == BranchStatus.FAILED;
            case VECTOR_ONLY -> ranking.vectorStatus() == BranchStatus.FAILED;
            case HYBRID, HYBRID_RERANK -> ranking.bm25Status() == BranchStatus.FAILED
                    && ranking.vectorStatus() == BranchStatus.FAILED;
        };
        if (failed) return RetrievalStatus.RETRIEVAL_FAILED;
        return ranking.candidates().isEmpty() ? RetrievalStatus.NO_RELIABLE_KNOWLEDGE
                : RetrievalStatus.GROUNDED;
    }

    /** 判断前五候选是否覆盖用例要求的全部精确技术词。 */
    private boolean requiredTermsPresent(List<String> required, List<RetrievalEvidence> candidates) {
        String corpus = candidates.stream().limit(5).map(value -> value.title() + "\n"
                + value.content() + "\n" + value.exactTerms()).reduce("", (a, b) -> a + "\n" + b)
                .toLowerCase(Locale.ROOT);
        return required.stream().allMatch(value -> corpus.contains(value.toLowerCase(Locale.ROOT)));
    }

    /** 生成与 JSONL 人工标注一致的稳定来源 ID。 */
    private String sourceId(RetrievalEvidence value) {
        return value.sourceType() + ":" + value.sourceId();
    }

    /** 按请求 ID 保持数据集顺序筛选，并拒绝未知或重复 ID。 */
    private List<RetrievalEvaluationCase> select(List<RetrievalEvaluationCase> all,
                                                  List<String> requested) {
        if (requested == null || requested.isEmpty()) return List.copyOf(all);
        Set<String> unique = new LinkedHashSet<>(requested);
        if (unique.size() != requested.size()) throw new IllegalArgumentException("评测用例 ID 不能重复");
        List<RetrievalEvaluationCase> selected = all.stream()
                .filter(value -> unique.contains(value.caseId())).toList();
        if (selected.size() != unique.size()) throw new IllegalArgumentException("包含未知评测用例 ID");
        return selected;
    }

    /** 原子替换内存运行快照并返回新值。 */
    private RetrievalEvaluationRun update(UUID runId, RetrievalMode mode,
                                          RetrievalEvaluationRun.Status status,
                                          int completed, int total,
                                          RetrievalEvaluationMetrics metric,
                                          List<RetrievalEvaluationCaseResult> results,
                                          String failure, java.time.Instant finishedAt) {
        RetrievalEvaluationRun original = runs.get(runId);
        RetrievalEvaluationRun value = new RetrievalEvaluationRun(runId, mode, status,
                completed, total, metric, List.copyOf(results), failure,
                original.startedAt(), finishedAt);
        runs.put(runId, value);
        return value;
    }

    /** 关闭后台虚拟线程执行器。 */
    @Override public void close() { executor.close(); }
}
