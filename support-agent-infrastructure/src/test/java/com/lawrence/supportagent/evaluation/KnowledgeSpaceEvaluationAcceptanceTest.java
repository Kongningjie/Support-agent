package com.lawrence.supportagent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.lawrence.supportagent.evaluation.KnowledgeSpaceEvaluationProbePort.Observation;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 使用完整六十条固定数据集验证隔离门槛聚合和动态报告链路。 */
class KnowledgeSpaceEvaluationAcceptanceTest {
    /** 全量固定场景的受控观测必须达到零泄漏和质量冻结门槛。 */
    @Test
    void shouldPassAllSixtyControlledIsolationScenarios() {
        JsonMapper mapper = JsonMapper.builder().build();
        KnowledgeSpaceEvaluationService service = new KnowledgeSpaceEvaluationService(
                new ClasspathKnowledgeSpaceEvaluationDatasetAdapter(mapper),
                this::controlledObservation,
                new TargetKnowledgeSpaceEvaluationReportAdapter(mapper),
                UUID::randomUUID, Instant::now, 1D);

        KnowledgeSpaceEvaluationReport report = service.run();

        assertThat(report.totalCases()).isEqualTo(60);
        assertThat(report.passed()).isTrue();
        assertThat(report.crossSpaceRerankCandidates()).isZero();
        assertThat(report.crossSpaceReferences()).isZero();
        assertThat(report.crossSpaceAnswerFacts()).isZero();
        assertThat(report.sourceDetailLeaks()).isZero();
        assertThat(report.permissionRejectionRate()).isEqualTo(1D);
        assertThat(report.recallAtFive()).isEqualTo(1D);
        assertThat(report.noHitAccuracy()).isEqualTo(1D);
        assertThat(report.exactTermRecall()).isEqualTo(1D);
        assertThat(report.enumerationLeaks()).isZero();
        assertThat(report.failedCaseIds()).isEmpty();
    }

    /** 根据人工冻结预期构造受控场景观测，不调用模型或保存测试正文。 */
    private Observation controlledObservation(KnowledgeSpaceEvaluationCase testCase) {
        boolean permissionRejected = testCase.category()
                == KnowledgeSpaceEvaluationCategory.PERMISSION_CHANGE;
        boolean noHit = "NO_HIT".equals(testCase.expectedResult());
        return new Observation(testCase.expectedSources(), testCase.expectedSources(),
                testCase.expectedSources(), testCase.expectedSources(),
                testCase.expectedSources(), testCase.requiredExactTerms(),
                permissionRejected, noHit, "资源不存在或无权访问");
    }
}
