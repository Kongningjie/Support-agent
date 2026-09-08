package com.lawrence.supportagent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.lawrence.supportagent.persistence.mapper.AgentAuditMapper;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/** 验证 MySQL 审计适配器对二进制标识的稳定编码。 */
class MySqlAgentAuditAdapterTest {
    /** UUID 必须按高低 64 位顺序写入 BINARY(16)，不得交给驱动自行推断。 */
    @Test
    void shouldEncodeUuidAsSixteenBytes() {
        AgentAuditMapper mapper = mock(AgentAuditMapper.class);
        MySqlAgentAuditAdapter adapter = new MySqlAgentAuditAdapter(mapper, JsonMapper.builder().build());
        UUID runId = UUID.fromString("12345678-1234-5678-90ab-cdef12345678");
        UUID conversationId = UUID.fromString("22345678-1234-5678-90ab-cdef12345678");
        UUID messageId = UUID.fromString("32345678-1234-5678-90ab-cdef12345678");
        ArgumentCaptor<byte[]> runBytes = ArgumentCaptor.forClass(byte[].class);

        adapter.start(runId, conversationId, messageId, Instant.EPOCH);

        verify(mapper).insertRun(runBytes.capture(), any(byte[].class), any(byte[].class), any());
        ByteBuffer buffer = ByteBuffer.wrap(runBytes.getValue());
        assertThat(new UUID(buffer.getLong(), buffer.getLong())).isEqualTo(runId);
        assertThat(runBytes.getValue()).hasSize(16);
    }
}
