package com.lawrence.supportagent.config;

import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceTelemetryPort;
import io.micrometer.core.instrument.MeterRegistry;

/** 将知识空间治理结果转换为只含固定枚举标签的 Micrometer 指标。 */
public class MicrometerKnowledgeSpaceTelemetryAdapter implements KnowledgeSpaceTelemetryPort {
    private final MeterRegistry registry;

    /** 注入应用统一指标注册表。 */
    public MicrometerKnowledgeSpaceTelemetryAdapter(MeterRegistry registry) {
        this.registry = registry;
    }

    /** {@inheritDoc} */
    @Override
    public void recordAccess(Action action, Result result, RequiredRole requiredRole,
                             Visibility visibility, boolean globalSpace, String reason) {
        registry.counter("support.agent.knowledge.space.access",
                "action", action.name(), "result", result.name(),
                "required_role", requiredRole.name(), "visibility", visibility.name(),
                "active_global", Boolean.toString(globalSpace),
                "reason", safeReason(reason)).increment();
    }

    /** {@inheritDoc} */
    @Override
    public void recordRetrievalScope(AllowedSpaceCount count) {
        registry.counter("support.agent.knowledge.space.retrieval.scope",
                "allowed_count", count.name()).increment();
    }

    /** {@inheritDoc} */
    @Override
    public void recordRebuild(RebuildResult result) {
        registry.counter("support.agent.knowledge.space.rebuild",
                "result", result.name()).increment();
    }

    /** 只保留冻结原因集合，未知值统一折叠，防止产生高基数标签。 */
    private String safeReason(String reason) {
        return switch (reason) {
            case "ALLOWED", "METADATA_ONLY", "ACCOUNT_INACTIVE", "SPACE_DISABLED",
                    "SPACE_NOT_FOUND", "MEMBERSHIP_MISSING", "ROLE_INSUFFICIENT" -> reason;
            default -> "UNKNOWN";
        };
    }
}
