package com.lawrence.supportagent.config;

import com.lawrence.supportagent.observability.OptimizationTelemetryPort;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** 使用 Micrometer 保存阶段六要求的低基数耗时、成功率和模型用量指标。 */
public class MicrometerOptimizationTelemetryAdapter implements OptimizationTelemetryPort {
    private final MeterRegistry registry;

    /** 注入应用统一指标注册表。 */
    public MicrometerOptimizationTelemetryAdapter(MeterRegistry registry) {
        this.registry = registry;
    }

    /** {@inheritDoc} */
    @Override
    public void recordDuration(Operation operation, long durationMs, boolean succeeded) {
        Timer.builder("support.agent.optimization.duration")
                .description("Support Agent 优化阶段耗时")
                .tag("operation", operation.name())
                .tag("outcome", succeeded ? "success" : "failure")
                .publishPercentiles(0.95)
                .publishPercentileHistogram()
                .serviceLevelObjectives(Duration.ofMillis(3_000), Duration.ofMillis(15_000),
                        Duration.ofMillis(20_000), Duration.ofMillis(30_000))
                .register(registry)
                .record(Math.max(0, durationMs), TimeUnit.MILLISECONDS);
    }

    /** {@inheritDoc} */
    @Override
    public void recordUsage(ModelOperation operation, long inputTokens, long outputTokens,
                            long itemCount, long characterCount) {
        recordSummary("support.agent.optimization.model.input.tokens", operation, inputTokens);
        recordSummary("support.agent.optimization.model.output.tokens", operation, outputTokens);
        recordSummary("support.agent.optimization.model.items", operation, itemCount);
        recordSummary("support.agent.optimization.model.characters", operation, characterCount);
    }

    /** 创建或复用指定模型用量分布指标。 */
    private void recordSummary(String name, ModelOperation operation, long value) {
        DistributionSummary.builder(name).description("Support Agent 模型聚合用量")
                .tag("operation", operation.name()).publishPercentiles(0.95)
                .register(registry).record(Math.max(0, value));
    }
}
