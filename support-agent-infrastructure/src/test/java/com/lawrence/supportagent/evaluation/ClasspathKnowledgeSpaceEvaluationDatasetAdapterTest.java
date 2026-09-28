package com.lawrence.supportagent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 验证阶段 20 固定空间隔离数据集的数量、分布和虚构字段。 */
class ClasspathKnowledgeSpaceEvaluationDatasetAdapterTest {
    /** 数据集必须严格包含冻结的六十条和五类最小分布。 */
    @Test
    void shouldLoadFrozenSixtyCases() {
        var cases = new ClasspathKnowledgeSpaceEvaluationDatasetAdapter(
                JsonMapper.builder().build()).load();
        Map<KnowledgeSpaceEvaluationCategory, Long> counts = cases.stream().collect(
                Collectors.groupingBy(KnowledgeSpaceEvaluationCase::category,
                        Collectors.counting()));

        assertThat(cases).hasSize(60);
        assertThat(counts).containsEntry(KnowledgeSpaceEvaluationCategory.GLOBAL_AND_ACTIVE, 15L)
                .containsEntry(KnowledgeSpaceEvaluationCategory.RESTRICTED_CONFLICT, 15L)
                .containsEntry(KnowledgeSpaceEvaluationCategory.PERMISSION_CHANGE, 10L)
                .containsEntry(KnowledgeSpaceEvaluationCategory.RESOURCE_INHERITANCE, 10L)
                .containsEntry(KnowledgeSpaceEvaluationCategory.ENUMERATION_AND_NORMAL, 10L);
        assertThat(cases).allSatisfy(value -> {
            assertThat(value.spaceCode()).matches("(GLOBAL|TEST_[A-Z]+)");
            assertThat(value.input()).doesNotContain("真实客户", "生产密码", "sk-");
        });
    }
}
