package com.lawrence.supportagent.evaluation;

import java.util.Comparator;
import java.util.List;

/** 按质量、稳定性、显著延迟差异、Token 和 Flash 偏好选择 Chat 模型。 */
public class AnswerModelSelectionPolicy {
    private static final double MEANINGFUL_DIFFERENCE = 0.10;

    /** 对候选结果应用阶段 8 冻结的非加权硬门禁和逐级比较规则。 */
    public AnswerModelSelection select(List<AnswerModelCandidateResult> candidates) {
        List<AnswerModelCandidateResult> qualified = candidates.stream()
                .filter(AnswerModelCandidateResult::qualityPassed).toList();
        if (qualified.isEmpty()) {
            return new AnswerModelSelection(AnswerModelSelection.Status.NO_QUALIFIED_CANDIDATE,
                    null, "所有候选在允许的稳定性复测后仍未通过质量硬门禁");
        }
        AnswerModelCandidateResult uniqueMax = qualified.stream()
                .filter(value -> value.modelName().contains("max") && value.uniqueQualityGain())
                .findFirst().orElse(null);
        if (uniqueMax != null) {
            return new AnswerModelSelection(AnswerModelSelection.Status.USER_DECISION_REQUIRED,
                    uniqueMax.modelName(), "Max 存在独有质量收益，成本差异必须由用户决定");
        }
        int minimumReruns = qualified.stream().mapToInt(value ->
                value.firstRoundAllPassed() ? 0 : Math.max(1, value.stabilityRerunCount()))
                .min().orElseThrow();
        List<AnswerModelCandidateResult> stable = qualified.stream()
                .filter(value -> (value.firstRoundAllPassed() ? 0
                        : Math.max(1, value.stabilityRerunCount())) == minimumReruns).toList();
        AnswerModelCandidateResult fastest = stable.stream().min(Comparator.comparingLong(
                AnswerModelCandidateResult::completeLatencyP95Ms)).orElseThrow();
        List<AnswerModelCandidateResult> latencyTied = stable.stream().filter(value ->
                relativeDifference(fastest.completeLatencyP95Ms(), value.completeLatencyP95Ms())
                        < MEANINGFUL_DIFFERENCE).toList();
        long minimumTokens = latencyTied.stream().mapToLong(
                AnswerModelCandidateResult::totalTokens).min().orElseThrow();
        List<AnswerModelCandidateResult> tokenTied = latencyTied.stream().filter(value ->
                relativeDifference(minimumTokens, value.totalTokens()) < MEANINGFUL_DIFFERENCE).toList();
        AnswerModelCandidateResult selected = tokenTied.stream()
                .min(Comparator.comparingInt(this::modelPreference)
                        .thenComparingLong(AnswerModelCandidateResult::totalTokens)).orElseThrow();
        return new AnswerModelSelection(AnswerModelSelection.Status.SELECTED,
                selected.modelName(), "全部质量门禁通过；依次比较稳定性、10% 显著延迟、Token 与 Flash 偏好");
    }

    /** 返回相对较小值的差异比例，零基线仅与零相同。 */
    private double relativeDifference(long baseline, long candidate) {
        if (baseline == 0) return candidate == 0 ? 0.0 : Double.POSITIVE_INFINITY;
        return Math.abs((double) candidate - baseline) / baseline;
    }

    /** 质量、稳定性、延迟和 Token 接近时优先 Flash，再选择其他候选。 */
    private int modelPreference(AnswerModelCandidateResult value) {
        return value.modelName().contains("flash") ? 0 : 1;
    }
}
