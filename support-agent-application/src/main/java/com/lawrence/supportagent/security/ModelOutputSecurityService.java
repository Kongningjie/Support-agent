package com.lawrence.supportagent.security;

import com.lawrence.supportagent.chat.AnswerValidator;
import com.lawrence.supportagent.knowledge.DocumentContentPolicy;
import com.lawrence.supportagent.knowledge.ExactTermExtractor;
import com.lawrence.supportagent.model.ModelInvocationSecurity;
import com.lawrence.supportagent.retrieval.RetrievalEvidence;
import com.lawrence.supportagent.sharedkernel.port.UuidGenerator;
import java.util.List;
import java.util.Objects;

/** 统一创建单次调用安全上下文，并在输出发送或持久化前执行安全决策。 */
public class ModelOutputSecurityService {
    private static final String CANARY_PREFIX = "SA-CANARY-";
    private final ModelOutputSecurityPolicy policy;
    private final LlmSecuritySettings settings;
    private final UuidGenerator ids;

    /** 注入输出策略、安全开关和随机标记生成器。 */
    public ModelOutputSecurityService(ModelOutputSecurityPolicy policy, LlmSecuritySettings settings,
                                      UuidGenerator ids) {
        this.policy = Objects.requireNonNull(policy, "模型输出安全策略不能为空");
        this.settings = Objects.requireNonNull(settings, "LLM 安全设置不能为空");
        this.ids = Objects.requireNonNull(ids, "随机标记生成器不能为空");
    }

    /** 为一次受保护调用生成独立安全上下文，反馈中只保留低基数规则编号。 */
    public ModelInvocationSecurity newInvocation(List<String> feedbackRules) {
        if (!settings.enabled()) {
            return new ModelInvocationSecurity(null, feedbackRules);
        }
        String canary = settings.promptCanaryEnabled()
                ? CANARY_PREFIX + ids.generate() : null;
        return new ModelInvocationSecurity(canary, feedbackRules);
    }

    /** 对完整输出执行统一安全策略。 */
    public ModelOutputAssessment assess(ModelOutputType type, String output,
                                        ModelInvocationSecurity invocation, String userMessage,
                                        List<RetrievalEvidence> evidence) {
        if (!settings.enabled()) {
            return new ModelOutputAssessment(ModelOutputAction.PASS, List.of());
        }
        return policy.assess(new ModelOutputRequest(type, output,
                invocation == null ? null : invocation.canary(), userMessage, evidence));
    }

    /** 判断首次可修复失败是否允许执行一次完整重生成。 */
    public boolean canRegenerate(int regenerations) {
        return regenerations < settings.maximumRegenerations();
    }

    /** 创建适用于兼容构造器和单元测试的冻结默认实现。 */
    public static ModelOutputSecurityService standard(AnswerValidator validator, UuidGenerator ids) {
        LlmSecuritySettings defaults = new LlmSecuritySettings(true, true, true, true, 1);
        return new ModelOutputSecurityService(
                new DeterministicModelOutputSecurityPolicy(validator), defaults, ids);
    }

    /** 创建无需外部策略依赖的冻结默认实现。 */
    public static ModelOutputSecurityService standard(UuidGenerator ids) {
        AnswerValidator validator = new AnswerValidator(
                new ExactTermExtractor(), new DocumentContentPolicy());
        return standard(validator, ids);
    }
}
