package com.lawrence.supportagent.agent.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 锁定阶段 10 摘要评测集的数量、分布、编号和内容哈希。 */
class ConversationSummaryEvaluationDatasetTest {
    private static final String SHA256 =
            "ee3d7d5fd5f9aaf14567eb524aeb54fcb788b0054174384eb22dc1b4025f4b5a";

    /** 数据集必须保持 40 条冻结分布且不得出现重复编号。 */
    @Test
    void shouldKeepFrozenDatasetDistributionAndHash() {
        var snapshot = new ConversationSummaryEvaluationDataset(new ObjectMapper()).load();
        Map<String, Long> distribution = snapshot.cases().stream().collect(
                Collectors.groupingBy(ConversationSummaryEvaluationDataset.Case::category,
                        Collectors.counting()));
        assertThat(snapshot.version()).isEqualTo("conversation-summary-eval-v1");
        assertThat(snapshot.sha256()).isEqualTo(SHA256);
        assertThat(snapshot.cases()).hasSize(40);
        assertThat(snapshot.cases().stream().map(ConversationSummaryEvaluationDataset.Case::caseId)
                .distinct()).hasSize(40);
        assertThat(distribution).containsEntry("REFERENCE", 8L)
                .containsEntry("DECISION", 8L).containsEntry("ENTITY", 8L)
                .containsEntry("CORRECTION", 6L).containsEntry("LONG", 6L)
                .containsEntry("MODEL_FAILURE", 1L).containsEntry("SCHEMA_INVALID", 1L)
                .containsEntry("ENTITY_HALLUCINATION", 1L).containsEntry("CAS_CONFLICT", 1L);
        assertThat(snapshot.cases().stream().filter(ConversationSummaryEvaluationDataset.Case::online))
                .hasSize(36);
    }
}
