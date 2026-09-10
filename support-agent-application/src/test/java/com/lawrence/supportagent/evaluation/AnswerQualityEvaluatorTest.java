package com.lawrence.supportagent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证回答事实、引用、精确值、拒答和 Schema 的确定性硬门禁。 */
class AnswerQualityEvaluatorTest {
    private final AnswerQualityEvaluator evaluator = new AnswerQualityEvaluator();

    /** 验证满足全部人工标注的知识回答通过。 */
    @Test
    void shouldPassGroundedAnswer() {
        AnswerEvaluationCase testCase = caseOf(false, AnswerEvaluationSchemaType.GROUNDED_ANSWER);
        AnswerQualityAssessment result = evaluator.evaluate(testCase,
                "标准值为 10 秒，应检查 server.port。[S1]", true);

        assertThat(result.passed()).isTrue();
        assertThat(result.failures()).isEmpty();
    }

    /** 验证缺失事实、禁用事实、未知引用和 Schema 错误不能被平均分掩盖。 */
    @Test
    void shouldRejectEveryHardGateViolation() {
        AnswerEvaluationCase testCase = caseOf(false, AnswerEvaluationSchemaType.GROUNDED_ANSWER);
        AnswerQualityAssessment result = evaluator.evaluate(testCase,
                "建议使用 30 秒并检查其他配置。[S2]", false);

        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).contains("SCHEMA_INVALID", "REQUIRED_FACT_MISSING",
                "FORBIDDEN_FACT_PRESENT", "EXACT_VALUE_MISSING", "UNKNOWN_CITATION");
    }

    /** 验证无知识用例必须返回固定克制语义且不得伪造引用。 */
    @Test
    void shouldRequireNoKnowledgeRefusal() {
        AnswerEvaluationCase testCase = new AnswerEvaluationCase("NOHIT", AnswerEvaluationCategory.NO_HIT,
                "未知问题", List.of(), List.of(), List.of("没有找到足够可靠的依据"), List.of("密码是"),
                List.of(), true, AnswerEvaluationSchemaType.NO_KNOWLEDGE);

        assertThat(evaluator.evaluate(testCase, "没有找到足够可靠的依据，请补充信息。", true).passed())
                .isTrue();
        assertThat(evaluator.evaluate(testCase, "密码是 123456。", true).passed()).isFalse();
    }

    /** 构造包含单一证据和精确值的知识回答用例。 */
    private AnswerEvaluationCase caseOf(boolean refusal, AnswerEvaluationSchemaType schema) {
        return new AnswerEvaluationCase("CASE", AnswerEvaluationCategory.EXACT, "标准值？",
                List.of(new AnswerEvaluationEvidence("DOC", "MANAGED_DOCUMENT", 1,
                        "标题", "标准值为 10 秒，配置键 server.port。")),
                List.of("S1"), List.of("10 秒", "server.port"), List.of("30 秒"),
                List.of("10 秒"), refusal, schema);
    }
}
