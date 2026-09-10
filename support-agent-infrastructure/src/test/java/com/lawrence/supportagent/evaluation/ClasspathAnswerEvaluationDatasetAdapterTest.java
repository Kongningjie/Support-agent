package com.lawrence.supportagent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 验证阶段 8 回答评测集的版本、哈希、分布和字段语义。 */
class ClasspathAnswerEvaluationDatasetAdapterTest {
    /** 加载冻结快照并断言三十条用例符合约定分布。 */
    @Test
    void shouldLoadFrozenAnswerEvaluationDataset() {
        AnswerEvaluationDatasetSnapshot snapshot = new ClasspathAnswerEvaluationDatasetAdapter(
                JsonMapper.builder().findAndAddModules().build()).load();

        assertThat(snapshot.version()).isEqualTo("answer-evaluation-v1.2");
        assertThat(snapshot.contentSha256()).hasSize(64);
        assertThat(snapshot.cases()).hasSize(30);
        Map<AnswerEvaluationCategory, Long> distribution = snapshot.cases().stream()
                .collect(Collectors.groupingBy(AnswerEvaluationCase::category,
                        Collectors.counting()));
        assertThat(distribution).containsExactlyInAnyOrderEntriesOf(Map.of(
                AnswerEvaluationCategory.GROUNDED, 10L,
                AnswerEvaluationCategory.EXACT, 5L,
                AnswerEvaluationCategory.NO_HIT, 5L,
                AnswerEvaluationCategory.CONFLICT, 5L,
                AnswerEvaluationCategory.CASE_GENERATION, 5L));
    }

    /** 验证每个字段均有明确验收语义且引用编号与证据顺序一致。 */
    @Test
    void shouldExposeDeterministicQualityGates() {
        AnswerEvaluationDatasetSnapshot snapshot = new ClasspathAnswerEvaluationDatasetAdapter(
                JsonMapper.builder().findAndAddModules().build()).load();

        assertThat(snapshot.cases()).allSatisfy(value -> {
            assertThat(value.caseId()).isNotBlank();
            assertThat(value.input()).isNotBlank();
            assertThat(value.requiredFacts()).isNotEmpty();
            assertThat(value.allowedCitations()).hasSize(value.evidence().size());
            assertThat(value.forbiddenFacts()).doesNotContainAnyElementsOf(value.requiredFacts());
        });
    }
}
