package com.lawrence.supportagent.evaluation;

import com.lawrence.supportagent.retrieval.RetrievalStatus;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 使用二元相关性计算固定检索指标，不设置质量硬阈值。 */
public class RetrievalMetricsCalculator {
    /** 根据完整用例及其同序结果计算五项汇总指标。 */
    public RetrievalEvaluationMetrics calculate(List<RetrievalEvaluationCase> cases,
                                                 List<RetrievalEvaluationCaseResult> results) {
        if (cases == null || results == null || cases.size() != results.size()) {
            throw new IllegalArgumentException("评测用例和结果数量必须一致");
        }
        double recall = 0;
        double reciprocalRank = 0;
        double ndcg = 0;
        int relevantCases = 0;
        int noHitCases = 0;
        int correctNoHit = 0;
        int exactCases = 0;
        int correctExact = 0;
        for (int index = 0; index < cases.size(); index++) {
            RetrievalEvaluationCase testCase = cases.get(index);
            RetrievalEvaluationCaseResult result = results.get(index);
            if (!testCase.relevantSourceIds().isEmpty()) {
                relevantCases++;
                Set<String> relevant = new HashSet<>(testCase.relevantSourceIds());
                List<String> ranking = result.rankedSourceIds();
                recall += ranking.stream().limit(5).distinct().filter(relevant::contains).count()
                        / (double) relevant.size();
                reciprocalRank += reciprocalRank(ranking, relevant);
                ndcg += ndcgAt5(ranking, relevant);
            }
            if (testCase.expectedStatus() == RetrievalStatus.NO_RELIABLE_KNOWLEDGE) {
                noHitCases++;
                if (result.actualStatus() == RetrievalStatus.NO_RELIABLE_KNOWLEDGE) correctNoHit++;
            }
            if (!testCase.requiredExactTerms().isEmpty()) {
                exactCases++;
                if (result.exactTermsSatisfied()) correctExact++;
            }
        }
        return new RetrievalEvaluationMetrics(divide(recall, relevantCases),
                divide(reciprocalRank, relevantCases), divide(ndcg, relevantCases),
                divide(correctNoHit, noHitCases), divide(correctExact, exactCases));
    }

    /** 返回前十中首个相关来源的倒数排名。 */
    private double reciprocalRank(List<String> ranking, Set<String> relevant) {
        for (int index = 0; index < Math.min(10, ranking.size()); index++) {
            if (relevant.contains(ranking.get(index))) return 1.0 / (index + 1);
        }
        return 0;
    }

    /** 计算前五名二元相关性的归一化折损累计增益。 */
    private double ndcgAt5(List<String> ranking, Set<String> relevant) {
        double dcg = 0;
        for (int index = 0; index < Math.min(5, ranking.size()); index++) {
            if (relevant.contains(ranking.get(index))) dcg += 1.0 / log2(index + 2);
        }
        double ideal = 0;
        for (int index = 0; index < Math.min(5, relevant.size()); index++) {
            ideal += 1.0 / log2(index + 2);
        }
        return ideal == 0 ? 0 : dcg / ideal;
    }

    /** 计算以二为底的对数。 */
    private double log2(double value) { return Math.log(value) / Math.log(2); }

    /** 安全计算均值，无样本时返回零。 */
    private double divide(double numerator, int denominator) {
        return denominator == 0 ? 0 : numerator / denominator;
    }
}
