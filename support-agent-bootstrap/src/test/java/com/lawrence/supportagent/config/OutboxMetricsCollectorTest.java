package com.lawrence.supportagent.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.persistence.mapper.AsyncTaskWorkflowMapper;
import com.lawrence.supportagent.persistence.record.AsyncTaskMetricsDO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 验证 Outbox 数据库快照转换为低基数 Micrometer Gauge。 */
class OutboxMetricsCollectorTest {
    /** 验证五项指标及最老任务等待时间按统一时钟更新。 */
    @Test
    void shouldPublishOperationalSnapshot() {
        AsyncTaskWorkflowMapper mapper = mock(AsyncTaskWorkflowMapper.class);
        AsyncTaskMetricsDO snapshot = new AsyncTaskMetricsDO();
        snapshot.backlog = 4;
        snapshot.oldestCreatedAt = Instant.parse("2026-09-19T00:00:00Z");
        snapshot.retryAttempts = 2;
        snapshot.dead = 1;
        snapshot.throughput = 7;
        when(mapper.metrics(any())).thenReturn(snapshot);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxMetricsCollector collector = new OutboxMetricsCollector(mapper,
                () -> Instant.parse("2026-09-19T00:02:00Z"), registry);

        collector.sample();

        assertEquals(4, value(registry, "support.agent.outbox.backlog"));
        assertEquals(120, value(registry, "support.agent.outbox.oldest.wait.seconds"));
        assertEquals(2, value(registry, "support.agent.outbox.retry.attempts"));
        assertEquals(1, value(registry, "support.agent.outbox.dead"));
        assertEquals(7, value(registry, "support.agent.outbox.throughput.per.minute"));
    }

    /** 读取测试注册表内指定 Gauge 的当前值。 */
    private double value(SimpleMeterRegistry registry, String name) {
        return registry.get(name).gauge().value();
    }
}
