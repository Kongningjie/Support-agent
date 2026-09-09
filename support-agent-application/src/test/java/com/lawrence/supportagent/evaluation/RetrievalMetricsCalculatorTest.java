package com.lawrence.supportagent.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lawrence.supportagent.retrieval.RetrievalStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证检索评测五项指标的确定性公式。 */
class RetrievalMetricsCalculatorTest {
    /** 验证相关来源排名、无命中和精确词命中的汇总结果。 */
    @Test
    void shouldCalculateFrozenMetrics() {
        List<RetrievalEvaluationCase> cases = List.of(
                new RetrievalEvaluationCase("KNOWN-001", "q1", List.of("D:1", "D:2"),
                        RetrievalStatus.GROUNDED, List.of(), "known"),
                new RetrievalEvaluationCase("NOHIT-001", "q2", List.of(),
                        RetrievalStatus.NO_RELIABLE_KNOWLEDGE, List.of(), "no hit"),
                new RetrievalEvaluationCase("EXACT-001", "q3", List.of("D:3"),
                        RetrievalStatus.GROUNDED, List.of("ERROR"), "exact"));
        List<RetrievalEvaluationCaseResult> results = List.of(
                new RetrievalEvaluationCaseResult("KNOWN-001", RetrievalStatus.GROUNDED,
                        RetrievalStatus.GROUNDED, List.of("X:1", "D:1"), true, null),
                new RetrievalEvaluationCaseResult("NOHIT-001", RetrievalStatus.NO_RELIABLE_KNOWLEDGE,
                        RetrievalStatus.NO_RELIABLE_KNOWLEDGE, List.of(), true, null),
                new RetrievalEvaluationCaseResult("EXACT-001", RetrievalStatus.GROUNDED,
                        RetrievalStatus.GROUNDED, List.of("D:3"), true, null));

        RetrievalEvaluationMetrics value = new RetrievalMetricsCalculator().calculate(cases, results);

        assertEquals(0.75, value.recallAt5(), 0.000001);
        assertEquals(0.75, value.mrrAt10(), 0.000001);
        double discountedSecond = 1.0 / (Math.log(3) / Math.log(2));
        assertEquals((discountedSecond / (1.0 + discountedSecond) + 1.0) / 2,
                value.ndcgAt5(), 0.000001);
        assertEquals(1, value.noHitAccuracy());
        assertEquals(1, value.exactTermRecall());
    }
}
