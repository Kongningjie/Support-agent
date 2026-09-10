package com.lawrence.supportagent.evaluation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 使用人工标注和确定性规则校验事实、引用、精确值、拒答及 Schema。 */
public class AnswerQualityEvaluator {
    private static final Pattern CITATION = Pattern.compile("\\[S(\\d+)]");

    /** 对一条完整答案执行全部不可被平均分掩盖的质量硬门禁。 */
    public AnswerQualityAssessment evaluate(AnswerEvaluationCase testCase, String answer,
                                            boolean schemaValid) {
        List<String> failures = new ArrayList<>();
        if (answer == null || answer.isBlank()) return failed("ANSWER_EMPTY");
        if (!schemaValid) failures.add("SCHEMA_INVALID");
        for (String required : testCase.requiredFacts()) {
            if (!answer.contains(required)) failures.add("REQUIRED_FACT_MISSING");
        }
        for (String forbidden : testCase.forbiddenFacts()) {
            if (answer.contains(forbidden)) failures.add("FORBIDDEN_FACT_PRESENT");
        }
        for (String exact : testCase.exactValues()) {
            if (!answer.contains(exact)) failures.add("EXACT_VALUE_MISSING");
        }
        validateCitations(testCase, answer, failures);
        if (testCase.expectedRefusal() && !answer.contains("没有找到足够可靠的依据")) {
            failures.add("REFUSAL_REQUIRED");
        }
        List<String> unique = failures.stream().distinct().toList();
        return new AnswerQualityAssessment(unique.isEmpty(), unique);
    }

    /** 校验知识回答必须引用且不得使用未授权证据编号。 */
    private void validateCitations(AnswerEvaluationCase testCase, String answer,
                                   List<String> failures) {
        Matcher matcher = CITATION.matcher(answer);
        Set<String> actual = new HashSet<>();
        while (matcher.find()) actual.add("S" + matcher.group(1));
        if (testCase.schemaType() == AnswerEvaluationSchemaType.GROUNDED_ANSWER
                && actual.isEmpty()) failures.add("CITATION_REQUIRED");
        if (!testCase.allowedCitations().containsAll(actual)) {
            failures.add("UNKNOWN_CITATION");
        }
    }

    /** 创建只包含一个稳定失败规则的结果。 */
    private AnswerQualityAssessment failed(String failure) {
        return new AnswerQualityAssessment(false, List.of(failure));
    }
}
