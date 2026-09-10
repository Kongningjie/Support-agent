package com.lawrence.supportagent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.lawrence.supportagent.retrieval.RetrievalParameters;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证阶段七参数矩阵只允许分组、可解释的单变量组实验。 */
class RetrievalExperimentMatrixTest {
    /** 候选覆盖必须与完整基线合并且不修改原映射。 */
    @Test
    void shouldResolveCandidateOnImmutableBaseline() {
        RetrievalExperimentMatrix matrix = new RetrievalExperimentMatrix("1.0",
                RetrievalParameters.baseline().asReportMap(), List.of(
                new RetrievalExperimentCandidate("threshold-040",
                        RetrievalExperimentGroup.GROUNDED_THRESHOLD,
                        "把全局可靠知识门槛提高到 0.40",
                        Map.of("rerankGroundedThreshold", "0.40"))));

        assertThat(matrix.resolve("threshold-040"))
                .containsEntry("rerankGroundedThreshold", "0.40")
                .containsEntry("bm25TopK", "30");
        assertThat(matrix.baselineParameters()).containsEntry("rerankGroundedThreshold", "0.3");
    }

    /** 阈值实验不得同时覆盖属于召回宽度组的参数。 */
    @Test
    void shouldRejectParametersFromAnotherVariableGroup() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new RetrievalExperimentMatrix("1.0",
                        RetrievalParameters.baseline().asReportMap(), List.of(
                        new RetrievalExperimentCandidate("invalid-threshold",
                                RetrievalExperimentGroup.GROUNDED_THRESHOLD,
                                "错误地同时修改召回宽度", Map.of(
                                "rerankGroundedThreshold", "0.40", "bm25TopK", "80")))));
    }
}
