package com.lawrence.supportagent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.lawrence.supportagent.retrieval.RetrievalDecisionReason;
import com.lawrence.supportagent.retrieval.RetrievalStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证可靠性门槛混淆计数和分类最高分范围。 */
class RetrievalEvaluationDiagnosticsTest {
    /** 同时统计误接收、误拒绝、技术故障和分类分数边界。 */
    @Test
    void shouldCalculateDecisionConfusionAndScoreRanges() {
        List<RetrievalEvaluationCase> cases = List.of(
                testCase("DIRECT-001", RetrievalStatus.GROUNDED, "DIRECT"),
                testCase("DIRECT-002", RetrievalStatus.GROUNDED, "DIRECT"),
                testCase("NOHIT-001", RetrievalStatus.NO_RELIABLE_KNOWLEDGE, "NO_HIT"),
                testCase("NOHIT-002", RetrievalStatus.NO_RELIABLE_KNOWLEDGE, "NO_HIT"));
        List<RetrievalEvaluationCaseResult> results = List.of(
                result("DIRECT-001", RetrievalStatus.GROUNDED, RetrievalStatus.GROUNDED, 0.8),
                result("DIRECT-002", RetrievalStatus.GROUNDED,
                        RetrievalStatus.NO_RELIABLE_KNOWLEDGE, 0.3),
                result("NOHIT-001", RetrievalStatus.NO_RELIABLE_KNOWLEDGE,
                        RetrievalStatus.NO_RELIABLE_KNOWLEDGE, 0.2),
                result("NOHIT-002", RetrievalStatus.NO_RELIABLE_KNOWLEDGE,
                        RetrievalStatus.GROUNDED, 0.6));

        RetrievalEvaluationDiagnostics value =
                RetrievalEvaluationDiagnostics.calculate(cases, results);

        assertThat(value.expectedGroundedCount()).isEqualTo(2);
        assertThat(value.correctGroundedCount()).isOne();
        assertThat(value.falseNoHitCount()).isOne();
        assertThat(value.expectedNoHitCount()).isEqualTo(2);
        assertThat(value.correctNoHitCount()).isOne();
        assertThat(value.falseGroundedCount()).isOne();
        assertThat(value.technicalFailureCount()).isZero();
        assertThat(value.highestRerankScoreByCategory().get("DIRECT"))
                .isEqualTo(new RetrievalEvaluationScoreRange(2, 0.3, 0.8));
    }

    /** 创建只保留诊断所需字段的固定用例。 */
    private RetrievalEvaluationCase testCase(String id, RetrievalStatus expected, String category) {
        return new RetrievalEvaluationCase(id, "问题", List.of(), expected, List.of(),
                "诊断", category, java.util.Map.of(),
                expected == RetrievalStatus.NO_RELIABLE_KNOWLEDGE, false, false);
    }

    /** 创建带最高分和最终状态的固定单条结果。 */
    private RetrievalEvaluationCaseResult result(String id, RetrievalStatus expected,
                                                  RetrievalStatus actual, double score) {
        RetrievalDecisionReason reason = actual == RetrievalStatus.GROUNDED
                ? RetrievalDecisionReason.RELIABLE_EVIDENCE_PRESENT
                : RetrievalDecisionReason.BELOW_GROUNDED_THRESHOLD;
        return new RetrievalEvaluationCaseResult(id, expected, actual, List.of(), true,
                score, actual == RetrievalStatus.GROUNDED ? 1 : 0, reason, null, 1);
    }
}
