package com.lawrence.supportagent.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lawrence.supportagent.observability.OptimizationTelemetryPort.ModelOperation;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort.Operation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 验证阶段六遥测只按固定低基数标签记录聚合数据。 */
class MicrometerOptimizationTelemetryAdapterTest {
    /** 验证耗时和模型用量进入预期指标且负值被归零。 */
    @Test
    void shouldRecordAggregateDurationAndUsage() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerOptimizationTelemetryAdapter adapter =
                new MicrometerOptimizationTelemetryAdapter(registry);

        adapter.recordDuration(Operation.RETRIEVAL, 25, true);
        adapter.recordUsage(ModelOperation.RERANK, 12, -1, 5, 80);

        assertEquals(25.0, registry.get("support.agent.optimization.duration")
                .tag("operation", "RETRIEVAL").tag("outcome", "success")
                .timer().totalTime(TimeUnit.MILLISECONDS));
        assertEquals(12.0, registry.get("support.agent.optimization.model.input.tokens")
                .tag("operation", "RERANK").summary().totalAmount());
        assertEquals(0.0, registry.get("support.agent.optimization.model.output.tokens")
                .tag("operation", "RERANK").summary().totalAmount());
        assertEquals(5.0, registry.get("support.agent.optimization.model.items")
                .tag("operation", "RERANK").summary().totalAmount());
        assertEquals(80.0, registry.get("support.agent.optimization.model.characters")
                .tag("operation", "RERANK").summary().totalAmount());
    }
}
