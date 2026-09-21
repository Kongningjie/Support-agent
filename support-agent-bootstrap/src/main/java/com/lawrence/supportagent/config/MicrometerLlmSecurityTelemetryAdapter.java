package com.lawrence.supportagent.config;

import com.lawrence.supportagent.security.LlmSecurityTelemetryPort;
import com.lawrence.supportagent.security.ModelOutputAction;
import com.lawrence.supportagent.security.ModelOutputRule;
import com.lawrence.supportagent.security.ModelOutputType;
import com.lawrence.supportagent.security.PromptSecurityAction;
import com.lawrence.supportagent.security.PromptSecuritySignal;
import com.lawrence.supportagent.security.PromptSecuritySource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Set;

/** 将 LLM 安全决策转换为只含冻结枚举标签的 Micrometer 指标。 */
public class MicrometerLlmSecurityTelemetryAdapter implements LlmSecurityTelemetryPort {
    private static final String NONE = "NONE";
    private final MeterRegistry registry;

    /** 注入应用统一指标注册表。 */
    public MicrometerLlmSecurityTelemetryAdapter(MeterRegistry registry) {
        this.registry = registry;
    }

    /** {@inheritDoc} */
    @Override
    public void recordPromptAssessment(PromptSecuritySource source, PromptSecurityAction action,
                                       Set<PromptSecuritySignal> signals) {
        if (signals == null || signals.isEmpty()) {
            promptCounter(source, action, NONE).increment();
            return;
        }
        signals.forEach(signal -> promptCounter(source, action, signal.name()).increment());
    }

    /** {@inheritDoc} */
    @Override
    public void recordOutputAssessment(ModelOutputType type, ModelOutputAction action,
                                       List<String> rules) {
        if (rules == null || rules.isEmpty()) {
            outputCounter(type, action, NONE).increment();
            return;
        }
        rules.forEach(rule -> outputCounter(type, action, safeRule(rule)).increment());
    }

    /** {@inheritDoc} */
    @Override
    public void recordRegeneration(ModelOutputType type) {
        Counter.builder("support.agent.security.output.regenerations")
                .tag("branch", type.name()).register(registry).increment();
    }

    /** {@inheritDoc} */
    @Override
    public void recordFinalRejection(ModelOutputType type) {
        Counter.builder("support.agent.security.output.final.rejections")
                .tag("branch", type.name()).register(registry).increment();
    }

    /** {@inheritDoc} */
    @Override
    public void recordContextExclusion(PromptSecuritySource source) {
        Counter.builder("support.agent.security.context.exclusions")
                .tag("source", source.name()).register(registry).increment();
    }

    /** 创建或复用输入与上下文安全评估计数器。 */
    private Counter promptCounter(PromptSecuritySource source, PromptSecurityAction action,
                                  String signal) {
        return Counter.builder("support.agent.security.prompt.assessments")
                .tag("source", source.name()).tag("action", action.name())
                .tag("signal", signal).register(registry);
    }

    /** 创建或复用输出安全评估计数器。 */
    private Counter outputCounter(ModelOutputType type, ModelOutputAction action, String rule) {
        return Counter.builder("support.agent.security.output.assessments")
                .tag("branch", type.name()).tag("action", action.name())
                .tag("rule", rule).register(registry);
    }

    /** 将未知规则折叠为固定值，防止自定义策略制造高基数标签。 */
    private String safeRule(String rule) {
        try {
            return ModelOutputRule.valueOf(rule).name();
        } catch (RuntimeException exception) {
            return NONE;
        }
    }
}
