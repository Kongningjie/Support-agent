package com.lawrence.supportagent.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

/** 验证检索参数快照的默认值、报告字段和跨字段约束。 */
class RetrievalParametersTest {
    /** 阶段七冻结参数必须完整出现在可复现报告参数中。 */
    @Test
    void shouldExposeCompleteBaselineReportMap() {
        RetrievalParameters parameters = RetrievalParameters.baseline();

        assertThat(parameters.asReportMap()).containsEntry("bm25TopK", "30")
                .containsEntry("vectorTopK", "30")
                .containsEntry("vectorCandidates", "100")
                .containsEntry("vectorMinimumSimilarity", "0.2")
                .containsEntry("rrfK", "20")
                .containsEntry("fusionTopK", "20")
                .containsEntry("rerankTopK", "20")
                .containsEntry("rankedTopK", "10")
                .containsEntry("finalTopK", "5")
                .containsEntry("rerankGroundedThreshold", "0.3")
                .containsEntry("analysisProfile", "ICU_ONLY")
                .containsEntry("exactTermWeight", "5.0");
    }

    /** 向量候选池不得小于向量分支实际需要返回的数量。 */
    @Test
    void shouldRejectVectorCandidatePoolSmallerThanTopK() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new RetrievalParameters(50, 80, 30, 0.2, 60,
                        30, 30, 10, 5, 0.35, RetrievalAnalysisProfile.ICU_ONLY,
                        3, 2, 1, 5));
    }

    /** 最终证据数不得突破一期最多五条的引用契约。 */
    @Test
    void shouldRejectMoreThanFiveFinalEvidenceItems() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new RetrievalParameters(50, 50, 200, 0.2, 60,
                        30, 30, 10, 6, 0.35, RetrievalAnalysisProfile.ICU_ONLY,
                        3, 2, 1, 5));
    }
}
