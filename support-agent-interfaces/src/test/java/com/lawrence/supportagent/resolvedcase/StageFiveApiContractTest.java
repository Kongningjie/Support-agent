package com.lawrence.supportagent.resolvedcase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lawrence.supportagent.evaluation.RetrievalEvaluationController;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 锁定阶段五案例正文、字符串 ID 和评测指标公共契约。 */
class StageFiveApiContractTest {
    /** 验证案例详情含完整内容，而摘要只包含检索和状态字段。 */
    @Test
    void shouldExposeCaseContentOnlyInDetails() {
        Map<String, Class<?>> details = components(ResolvedCaseController.CaseResponse.class);
        Map<String, Class<?>> summary = components(ResolvedCaseController.CaseSummaryResponse.class);

        assertTrue(details.containsKey("problem"));
        assertTrue(details.containsKey("cause"));
        assertTrue(details.containsKey("solution"));
        assertFalse(summary.containsKey("problem"));
        assertEquals(String.class, details.get("caseId"));
    }

    /** 验证评测响应固定暴露五项指标。 */
    @Test
    void shouldExposeAllFrozenEvaluationMetrics() {
        Map<String, Class<?>> metrics = components(
                RetrievalEvaluationController.MetricsResponse.class);

        assertEquals(5, metrics.size());
        assertTrue(metrics.keySet().containsAll(java.util.Set.of("recallAt5", "mrrAt10",
                "ndcgAt5", "noHitAccuracy", "exactTermRecall")));
    }

    /** 验证拒绝与归档请求使用含义明确且彼此独立的原因字段。 */
    @Test
    void shouldExposeActionSpecificReasonFields() {
        Map<String, Class<?>> reject = components(ResolvedCaseController.RejectRequest.class);
        Map<String, Class<?>> archive = components(ResolvedCaseController.ArchiveRequest.class);

        assertTrue(reject.containsKey("rejectionReason"));
        assertFalse(reject.containsKey("reason"));
        assertTrue(archive.containsKey("archiveReason"));
        assertFalse(archive.containsKey("reason"));
    }

    /** 返回响应 Record 的字段名和 Java 类型。 */
    private Map<String, Class<?>> components(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).collect(Collectors.toUnmodifiableMap(
                RecordComponent::getName, RecordComponent::getType));
    }
}
