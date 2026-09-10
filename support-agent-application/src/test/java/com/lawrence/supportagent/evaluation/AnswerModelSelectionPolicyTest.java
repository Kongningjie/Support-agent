package com.lawrence.supportagent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证阶段 8 模型选择规则的优先级和人工决策边界。 */
class AnswerModelSelectionPolicyTest {
    private final AnswerModelSelectionPolicy policy = new AnswerModelSelectionPolicy();

    /** 验证首轮全通过优先于需要稳定性复测的更快候选。 */
    @Test
    void shouldPreferFirstRoundPassBeforeLatency() {
        AnswerModelSelection selection = policy.select(List.of(
                candidate("qwen3.8-flash", true, true, 0, 20000, 30000, false),
                candidate("qwen3.7-flash", true, false, 1, 10000, 20000, false)));
        assertThat(selection.modelName()).isEqualTo("qwen3.8-flash");
    }

    /** 验证达到百分之十的延迟优势优先于 Token 差异。 */
    @Test
    void shouldPreferMeaningfullyFasterCandidate() {
        AnswerModelSelection selection = policy.select(List.of(
                candidate("qwen3.7-flash", true, true, 0, 9000, 40000, false),
                candidate("qwen3.8-flash", true, true, 0, 10000, 20000, false)));
        assertThat(selection.modelName()).isEqualTo("qwen3.7-flash");
    }

    /** 验证延迟与 Token 接近时选择 Flash。 */
    @Test
    void shouldPreferFlashWhenMetricsAreClose() {
        AnswerModelSelection selection = policy.select(List.of(
                candidate("qwen3.8-max-0902", true, true, 0, 10000, 10000, false),
                candidate("qwen3.8-flash", true, true, 0, 10500, 10500, false)));
        assertThat(selection.modelName()).isEqualTo("qwen3.8-flash");
    }

    /** 验证 Max 存在独有质量收益时停止自动选择。 */
    @Test
    void shouldRequestDecisionForUniqueMaxQualityGain() {
        AnswerModelSelection selection = policy.select(List.of(
                candidate("qwen3.8-max-0902", true, true, 0, 10000, 30000, true),
                candidate("qwen3.8-flash", true, true, 0, 9000, 20000, false)));
        assertThat(selection.status()).isEqualTo(
                AnswerModelSelection.Status.USER_DECISION_REQUIRED);
    }

    /** 创建一个供选择规则使用的最小候选结果。 */
    private AnswerModelCandidateResult candidate(String model, boolean qualityPassed,
                                                  boolean firstRoundPassed, int reruns,
                                                  long latency, long tokens,
                                                  boolean uniqueQualityGain) {
        return new AnswerModelCandidateResult(model, qualityPassed, firstRoundPassed,
                reruns, latency, tokens, uniqueQualityGain);
    }
}
