package com.lawrence.supportagent.evaluation;

import com.lawrence.supportagent.retrieval.RetrievalStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 保存可靠性门槛的混淆计数和分类分数范围，与五项冻结排名指标相互独立。
 *
 * @param expectedGroundedCount 人工预期为有可靠知识的用例数量
 * @param correctGroundedCount 有知识且被正确接受的用例数量
 * @param falseNoHitCount 有知识但被错误拒绝为无知识的用例数量
 * @param expectedNoHitCount 人工预期为无可靠知识的用例数量
 * @param correctNoHitCount 无知识且被正确拒绝的用例数量
 * @param falseGroundedCount 无知识但被错误接受的用例数量
 * @param technicalFailureCount 本次实际判定为检索技术故障的用例数量
 * @param highestRerankScoreByCategory 各冻结问题分类的候选最高分范围
 */
public record RetrievalEvaluationDiagnostics(
        int expectedGroundedCount, int correctGroundedCount, int falseNoHitCount,
        int expectedNoHitCount, int correctNoHitCount, int falseGroundedCount,
        int technicalFailureCount,
        Map<String, RetrievalEvaluationScoreRange> highestRerankScoreByCategory) {

    /** 根据同序用例和结果计算不包含敏感正文的可靠性诊断。 */
    public static RetrievalEvaluationDiagnostics calculate(
            List<RetrievalEvaluationCase> cases,
            List<RetrievalEvaluationCaseResult> results) {
        if (cases == null || results == null || cases.size() != results.size()) {
            throw new IllegalArgumentException("评测用例和结果数量必须一致");
        }
        int expectedGrounded = 0;
        int correctGrounded = 0;
        int falseNoHit = 0;
        int expectedNoHit = 0;
        int correctNoHit = 0;
        int falseGrounded = 0;
        int technicalFailure = 0;
        Map<String, MutableScoreRange> ranges = new LinkedHashMap<>();
        for (int index = 0; index < cases.size(); index++) {
            RetrievalEvaluationCase testCase = cases.get(index);
            RetrievalEvaluationCaseResult result = results.get(index);
            if (testCase.expectedStatus() == RetrievalStatus.GROUNDED) {
                expectedGrounded++;
                if (result.actualStatus() == RetrievalStatus.GROUNDED) correctGrounded++;
                if (result.actualStatus() == RetrievalStatus.NO_RELIABLE_KNOWLEDGE) falseNoHit++;
            } else if (testCase.expectedStatus() == RetrievalStatus.NO_RELIABLE_KNOWLEDGE) {
                expectedNoHit++;
                if (result.actualStatus() == RetrievalStatus.NO_RELIABLE_KNOWLEDGE) correctNoHit++;
                if (result.actualStatus() == RetrievalStatus.GROUNDED) falseGrounded++;
            }
            if (result.actualStatus() == RetrievalStatus.RETRIEVAL_FAILED) technicalFailure++;
            if (result.highestRerankScore() != null) {
                ranges.computeIfAbsent(testCase.category(), ignored -> new MutableScoreRange())
                        .accept(result.highestRerankScore());
            }
        }
        Map<String, RetrievalEvaluationScoreRange> immutableRanges = new LinkedHashMap<>();
        ranges.forEach((category, range) -> immutableRanges.put(category, range.toImmutable()));
        return new RetrievalEvaluationDiagnostics(expectedGrounded, correctGrounded, falseNoHit,
                expectedNoHit, correctNoHit, falseGrounded, technicalFailure,
                Map.copyOf(immutableRanges));
    }

    /** 在计算期间累积一个问题分类的最高候选分数。 */
    private static final class MutableScoreRange {
        private int count;
        private double minimum = Double.POSITIVE_INFINITY;
        private double maximum = Double.NEGATIVE_INFINITY;

        /** 接收一个已经由检索服务校验为零到一的有限分数。 */
        private void accept(double score) {
            count++;
            minimum = Math.min(minimum, score);
            maximum = Math.max(maximum, score);
        }

        /** 转换为可安全写入不可变报告的分数范围。 */
        private RetrievalEvaluationScoreRange toImmutable() {
            return new RetrievalEvaluationScoreRange(count, minimum, maximum);
        }
    }
}
