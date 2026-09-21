package com.lawrence.supportagent.evaluation;

import com.lawrence.supportagent.model.ModelInvocationSecurity;
import com.lawrence.supportagent.security.ModelOutputAction;
import com.lawrence.supportagent.security.ModelOutputAssessment;
import com.lawrence.supportagent.security.ModelOutputSecurityService;
import com.lawrence.supportagent.security.ModelOutputType;
import com.lawrence.supportagent.security.PromptSecurityAssessment;
import com.lawrence.supportagent.security.PromptSecurityPolicy;
import com.lawrence.supportagent.security.PromptSecuritySource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 使用生产安全策略离线执行固定数据集并计算冻结门禁。 */
public class SecurityEvaluationService {
    private static final String EVALUATION_CANARY = "SA-CANARY-EVALUATION";
    private final SecurityEvaluationDatasetPort dataset;
    private final PromptSecurityPolicy promptPolicy;
    private final ModelOutputSecurityService outputSecurity;

    /** 注入固定数据集和与生产一致的输入、输出安全策略。 */
    public SecurityEvaluationService(SecurityEvaluationDatasetPort dataset,
                                     PromptSecurityPolicy promptPolicy,
                                     ModelOutputSecurityService outputSecurity) {
        this.dataset = Objects.requireNonNull(dataset, "安全评测数据集不能为空");
        this.promptPolicy = Objects.requireNonNull(promptPolicy, "Prompt 安全策略不能为空");
        this.outputSecurity = Objects.requireNonNull(outputSecurity, "输出安全服务不能为空");
    }

    /** 执行全部样本并返回不含正文的聚合报告。 */
    public SecurityEvaluationReport evaluate() {
        List<SecurityEvaluationCase> cases = dataset.load();
        int direct = 0;
        int directBlocked = 0;
        int indirectLeaks = 0;
        int outputEscapes = 0;
        int normal = 0;
        int normalFalseBlocks = 0;
        List<String> failures = new ArrayList<>();
        for (SecurityEvaluationCase value : cases) {
            Result result = evaluate(value);
            if (value.category() == SecurityEvaluationCategory.DIRECT_INJECTION) {
                direct++;
                if (result.action() == SecurityEvaluationAction.BLOCK) directBlocked++;
            } else if (value.category() == SecurityEvaluationCategory.INDIRECT_INJECTION
                    && result.action() != SecurityEvaluationAction.BLOCK) {
                indirectLeaks++;
            } else if (value.category() == SecurityEvaluationCategory.OUTPUT_LEAK
                    && result.action() != SecurityEvaluationAction.REJECT) {
                outputEscapes++;
            } else if (value.category() == SecurityEvaluationCategory.NORMAL_HARD) {
                normal++;
                if (result.action() == SecurityEvaluationAction.BLOCK
                        || result.action() == SecurityEvaluationAction.REJECT) {
                    normalFalseBlocks++;
                }
            }
            if (!matches(value, result)) failures.add(value.caseId());
        }
        return new SecurityEvaluationReport(cases.size(), ratio(directBlocked, direct),
                indirectLeaks, outputEscapes, ratio(normalFalseBlocks, normal), failures);
    }

    /** 对单条样本选择输入或输出生产策略并标准化动作与规则编号。 */
    private Result evaluate(SecurityEvaluationCase value) {
        if (value.source() == SecurityEvaluationSource.MODEL_OUTPUT) {
            ModelOutputAssessment assessment = outputSecurity.assess(ModelOutputType.GREETING,
                    value.input(), new ModelInvocationSecurity(EVALUATION_CANARY, List.of()),
                    "固定安全评测", List.of());
            return new Result(map(assessment.action()), names(assessment.feedbackRules()));
        }
        PromptSecurityAssessment assessment = promptPolicy.assess(
                value.input(), promptSource(value.source()));
        return new Result(SecurityEvaluationAction.valueOf(assessment.action().name()),
                names(assessment.signals()));
    }

    /** 判断动作、必需信号和禁止信号是否均符合样本契约。 */
    private boolean matches(SecurityEvaluationCase value, Result result) {
        return result.action() == value.expectedAction()
                && result.signals().containsAll(value.requiredSignals())
                && value.forbiddenSignals().stream().noneMatch(result.signals()::contains);
    }

    /** 将评测来源映射到生产 Prompt 安全来源。 */
    private PromptSecuritySource promptSource(SecurityEvaluationSource source) {
        return switch (source) {
            case USER_MESSAGE -> PromptSecuritySource.USER_MESSAGE;
            case DOCUMENT, RESOLVED_CASE -> PromptSecuritySource.EVIDENCE;
            case TICKET_FIELD -> PromptSecuritySource.TICKET_FIELD;
            case HISTORY -> PromptSecuritySource.HISTORY;
            case SUMMARY -> PromptSecuritySource.SUMMARY;
            case MEMORY -> PromptSecuritySource.MEMORY;
            case MODEL_OUTPUT -> throw new IllegalArgumentException("模型输出不能映射为 Prompt 来源");
        };
    }

    /** 将输出动作映射为统一评测动作。 */
    private SecurityEvaluationAction map(ModelOutputAction action) {
        return SecurityEvaluationAction.valueOf(action.name());
    }

    /** 将冻结枚举集合转换为稳定名称集合。 */
    private Set<String> names(Iterable<?> values) {
        Set<String> names = new HashSet<>();
        values.forEach(value -> names.add(value instanceof Enum<?> enumeration
                ? enumeration.name() : String.valueOf(value)));
        return Set.copyOf(names);
    }

    /** 安全计算命中比例，空分母返回零。 */
    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    /** 保存单条评测的标准化结果，不携带原始正文。 */
    private record Result(SecurityEvaluationAction action, Set<String> signals) { }
}
