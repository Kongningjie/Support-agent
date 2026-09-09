package com.lawrence.supportagent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.retrieval.BranchStatus;
import com.lawrence.supportagent.retrieval.RetrievalEvidence;
import com.lawrence.supportagent.retrieval.RetrievalMode;
import com.lawrence.supportagent.retrieval.RetrievalRanking;
import com.lawrence.supportagent.retrieval.RetrievalService;
import com.lawrence.supportagent.retrieval.RetrievalStatus;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 验证固定评测后台运行、四模式报告和只读指标汇总。 */
class RetrievalEvaluationServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-09T01:00:00Z");

    /** 验证四种模式都能使用同一数据集生成可重复的五项指标报告。 */
    @Test
    void shouldGenerateReportsForAllFourModes() throws InterruptedException {
        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.rank(anyString(), any())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            RetrievalMode mode = invocation.getArgument(1);
            return ranking(mode, query.equals("未知问题") ? List.of() : List.of(evidence()));
        });
        List<RetrievalEvaluationCase> cases = List.of(
                new RetrievalEvaluationCase("KNOWN-001", "已知问题",
                        List.of("MANAGED_DOCUMENT:1"), RetrievalStatus.GROUNDED,
                        List.of("ERROR_CODE"), "验证已知知识和精确词"),
                new RetrievalEvaluationCase("NOHIT-001", "未知问题", List.of(),
                        RetrievalStatus.NO_RELIABLE_KNOWLEDGE, List.of(), "验证无知识判断"));
        LinkedBlockingQueue<RetrievalEvaluationRun> reports = new LinkedBlockingQueue<>();
        AtomicInteger sequence = new AtomicInteger();
        RetrievalEvaluationDatasetSnapshot snapshot = new RetrievalEvaluationDatasetSnapshot(
                EvaluationDatasetKind.LOCKED_REGRESSION, "test-v1", "abc", cases,
                java.util.Map.of("MANAGED_DOCUMENT\u0000故障标准", "MANAGED_DOCUMENT:1"));
        try (RetrievalEvaluationService service = new RetrievalEvaluationService(ignored -> snapshot,
                reports::add, retrieval, new RetrievalMetricsCalculator(),
                () -> new UUID(0, sequence.incrementAndGet()), () -> NOW)) {
            for (RetrievalMode mode : RetrievalMode.values()) {
                service.start(mode, List.of());
                RetrievalEvaluationRun report = reports.poll(5, TimeUnit.SECONDS);

                assertThat(report).isNotNull();
                assertThat(report.mode()).isEqualTo(mode);
                assertThat(report.status()).isEqualTo(RetrievalEvaluationRun.Status.SUCCEEDED);
                assertThat(report.metrics()).isEqualTo(
                        new RetrievalEvaluationMetrics(1, 1, 1, 1, 1));
            }
        }
    }

    /** 创建与模式相符的分支状态及给定候选。 */
    private RetrievalRanking ranking(RetrievalMode mode, List<RetrievalEvidence> candidates) {
        BranchStatus bm25 = mode == RetrievalMode.VECTOR_ONLY
                ? BranchStatus.SKIPPED : BranchStatus.SUCCEEDED;
        BranchStatus vector = mode == RetrievalMode.BM25_ONLY
                ? BranchStatus.SKIPPED : BranchStatus.SUCCEEDED;
        BranchStatus rerank = mode == RetrievalMode.HYBRID_RERANK
                ? BranchStatus.SUCCEEDED : BranchStatus.SKIPPED;
        return new RetrievalRanking(mode, candidates, bm25, vector, rerank);
    }

    /** 创建同时命中相关来源和精确词的固定候选。 */
    private RetrievalEvidence evidence() {
        return new RetrievalEvidence("chunk-1", "MANAGED_DOCUMENT", 1, 1,
                "故障标准", "错误码", "ERROR_CODE 对应处理步骤", List.of(), Set.of(),
                1, 1, 0.03, 0.9);
    }
}
