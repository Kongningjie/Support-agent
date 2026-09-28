package com.lawrence.supportagent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.lawrence.supportagent.evaluation.KnowledgeSpaceEvaluationProbePort.Observation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 验证空间隔离评测的零泄漏与质量门槛计算。 */
class KnowledgeSpaceEvaluationServiceTest {
    /** 完全满足预期时应通过并写出一次报告。 */
    @Test
    void shouldPassAllFrozenThresholds() {
        List<KnowledgeSpaceEvaluationCase> cases = cases();
        List<KnowledgeSpaceEvaluationReport> reports = new ArrayList<>();
        KnowledgeSpaceEvaluationService service = new KnowledgeSpaceEvaluationService(
                () -> cases, this::passingObservation, reports::add,
                () -> UUID.fromString("90000000-0000-0000-0000-000000000001"),
                () -> Instant.parse("2026-09-27T00:00:00Z"), 1D);

        KnowledgeSpaceEvaluationReport report = service.run();

        assertThat(report.passed()).isTrue();
        assertThat(report.crossSpaceRerankCandidates()).isZero();
        assertThat(report.permissionRejectionRate()).isEqualTo(1D);
        assertThat(report.recallAtFive()).isEqualTo(1D);
        assertThat(report.noHitAccuracy()).isEqualTo(1D);
        assertThat(report.exactTermRecall()).isEqualTo(1D);
        assertThat(reports).containsExactly(report);
    }

    /** 任一禁止来源进入 Rerank 时必须失败并定位稳定用例编号。 */
    @Test
    void shouldFailWhenForbiddenSourceReachesRerank() {
        List<KnowledgeSpaceEvaluationCase> cases = cases();
        KnowledgeSpaceEvaluationService service = new KnowledgeSpaceEvaluationService(
                () -> cases, value -> {
                    Observation passing = passingObservation(value);
                    return value.caseId().equals("KS-CONFLICT-001")
                            ? new Observation(List.of("BETA-1"), passing.citationSources(),
                            passing.answerFactSources(), passing.detailSources(),
                            passing.topFiveSources(), passing.returnedExactTerms(),
                            passing.permissionRejected(), passing.noHit(), passing.safeResponse())
                            : passing;
                }, ignored -> { }, UUID::randomUUID, Instant::now, 1D);

        KnowledgeSpaceEvaluationReport report = service.run();

        assertThat(report.passed()).isFalse();
        assertThat(report.crossSpaceRerankCandidates()).isEqualTo(1);
        assertThat(report.failedCaseIds()).containsExactly("KS-CONFLICT-001");
    }

    /** 为服务聚合测试创建覆盖权限、冲突和无命中的最小用例集。 */
    private List<KnowledgeSpaceEvaluationCase> cases() {
        return List.of(
                testCase("KS-GLOBAL-001", KnowledgeSpaceEvaluationCategory.GLOBAL_AND_ACTIVE,
                        List.of("GLOBAL-1", "ALPHA-1"), List.of("BETA-1"),
                        List.of("ERROR_A"), "ALLOW"),
                testCase("KS-CONFLICT-001", KnowledgeSpaceEvaluationCategory.RESTRICTED_CONFLICT,
                        List.of("ALPHA-1"), List.of("BETA-1"), List.of(), "ALLOW"),
                testCase("KS-PERMISSION-001", KnowledgeSpaceEvaluationCategory.PERMISSION_CHANGE,
                        List.of(), List.of("ALPHA-1"), List.of(), "DENY"),
                testCase("KS-ENUM-001", KnowledgeSpaceEvaluationCategory.ENUMERATION_AND_NORMAL,
                        List.of(), List.of("BETA-1"), List.of(), "NO_HIT"));
    }

    /** 创建一条只含虚构来源的测试用例。 */
    private KnowledgeSpaceEvaluationCase testCase(String id,
                                                   KnowledgeSpaceEvaluationCategory category,
                                                   List<String> expected, List<String> forbidden,
                                                   List<String> exactTerms, String result) {
        return new KnowledgeSpaceEvaluationCase(id, category, "TEST_ALPHA", "READER",
                "固定输入", expected, forbidden, List.of("TEST_BETA"), exactTerms, result);
    }

    /** 按固定预期构造无泄漏观测。 */
    private Observation passingObservation(KnowledgeSpaceEvaluationCase value) {
        return new Observation(value.expectedSources(), value.expectedSources(),
                value.expectedSources(), value.expectedSources(), value.expectedSources(),
                value.requiredExactTerms(),
                value.category() == KnowledgeSpaceEvaluationCategory.PERMISSION_CHANGE,
                "NO_HIT".equals(value.expectedResult()), "资源不可访问");
    }
}
