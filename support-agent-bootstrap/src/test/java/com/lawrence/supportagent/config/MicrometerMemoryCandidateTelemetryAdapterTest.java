package com.lawrence.supportagent.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lawrence.supportagent.memory.CandidateInsertOutcome;
import com.lawrence.supportagent.memory.port.MemoryCandidateTelemetryPort.CandidateFailureReason;
import com.lawrence.supportagent.memory.port.MemoryCandidateTelemetryPort.CandidateOutcome;
import com.lawrence.supportagent.memory.port.MemoryCandidateTelemetryPort.CandidateRejectionReason;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 验证候选 Micrometer 指标分类、当前执行数和标签安全边界。 */
class MicrometerMemoryCandidateTelemetryAdapterTest {
    /** 全部指标只使用冻结低基数标签，不携带用户、会话或正文。 */
    @Test void shouldPublishOnlyLowCardinalityMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerMemoryCandidateTelemetryAdapter telemetry =
                new MicrometerMemoryCandidateTelemetryAdapter(registry);

        telemetry.recordRequest();
        telemetry.recordOutcome(CandidateOutcome.SUCCESS);
        telemetry.recordOutcome(CandidateOutcome.EMPTY);
        telemetry.recordRejection(CandidateRejectionReason.GLOBAL_LIMIT);
        telemetry.recordRejection(CandidateRejectionReason.USER_LIMIT);
        telemetry.recordFailure(CandidateFailureReason.MODEL);
        telemetry.recordInsertion(CandidateInsertOutcome.DUPLICATE);
        telemetry.executionStarted();
        telemetry.recordDuration(12);
        telemetry.executionFinished();
        telemetry.recordCleanup(7, 21, true);
        telemetry.recordCleanup(0, 4, false);

        assertEquals(1.0, registry.get("support.agent.memory.candidate.requests")
                .counter().count());
        assertEquals(0.0, registry.get("support.agent.memory.candidate.inflight")
                .gauge().value());
        assertEquals(7.0, registry.get("support.agent.memory.cleanup.deleted")
                .summary().totalAmount());
        Set<String> allowedTags = Set.of("outcome", "reason", "phi");
        for (Meter meter : registry.getMeters()) {
            assertTrue(meter.getId().getTags().stream()
                    .allMatch(tag -> allowedTags.contains(tag.getKey())));
            assertFalse(meter.getId().getTags().stream().anyMatch(tag ->
                    tag.getValue().contains("user") || tag.getValue().contains("conversation")
                            || tag.getValue().contains("message")));
        }
    }
}
