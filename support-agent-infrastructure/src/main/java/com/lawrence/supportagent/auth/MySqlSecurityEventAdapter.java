package com.lawrence.supportagent.auth;

import com.lawrence.supportagent.auth.port.SecurityEventPort;
import com.lawrence.supportagent.persistence.mapper.SecurityEventMapper;
import com.lawrence.supportagent.persistence.record.SecurityEventDO;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** 使用 MySQL 持久化最小化账号安全事件。 */
@Repository
public class MySqlSecurityEventAdapter implements SecurityEventPort {
    private final SecurityEventMapper mapper;
    private final MeterRegistry metrics;

    /** 注入安全事件 Mapper。 */
    public MySqlSecurityEventAdapter(SecurityEventMapper mapper, MeterRegistry metrics) {
        this.mapper = mapper;
        this.metrics = metrics;
    }

    /** {@inheritDoc} */
    @Override public void record(SecurityEventType type, UUID targetUserId, String actorId,
                                 String result, String reason, String sourceHash,
                                 Instant occurredAt) {
        SecurityEventDO event = new SecurityEventDO();
        event.eventType = type.name();
        event.targetUserId = targetUserId == null ? null : bytes(targetUserId);
        event.actorId = actorId;
        event.result = result;
        event.reason = reason;
        event.sourceHash = sourceHash;
        event.occurredAt = occurredAt;
        if (mapper.insert(event) != 1) {
            throw new IllegalStateException("安全事件未成功写入");
        }
        metrics.counter("support.agent.security.events", "type", type.name(),
                "result", result, "reason", reason).increment();
    }

    /** 把公开 UUID 编码为 MySQL BINARY(16)。 */
    private byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }
}
