package com.lawrence.supportagent.agent.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lawrence.supportagent.memory.MemoryType;
import com.lawrence.supportagent.model.ModelInvocationException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 验证长期记忆候选适配器的严格 JSON 边界，不调用真实模型。 */
class DashScopeUserMemoryCandidateAdapterTest {
    private final DashScopeUserMemoryCandidateAdapter adapter =
            new DashScopeUserMemoryCandidateAdapter("test-key", "qwen3.7-flash",
                    "https://dashscope.aliyuncs.com/api/v1", JsonMapper.builder().build(),
                    Duration.ofSeconds(1), 400);

    /** 合法响应只允许零到三条冻结类型候选。 */
    @Test void shouldParseStrictCandidates() {
        var result = adapter.parse("""
                {"candidates":[{"memoryType":"CONSTRAINT","content":"统一使用 PowerShell 7"}]}
                """);
        assertEquals(1, result.size());
        assertEquals(MemoryType.CONSTRAINT, result.getFirst().memoryType());
    }

    /** 多余字段、未知类型和超过三条候选必须整体拒绝。 */
    @Test void shouldRejectInvalidSchema() {
        assertThrows(ModelInvocationException.class, () -> adapter.parse(
                "{\"candidates\":[],\"extra\":true}"));
        assertThrows(ModelInvocationException.class, () -> adapter.parse(
                "{\"candidates\":[{\"memoryType\":\"OTHER\",\"content\":\"x\"}]}"));
    }
}
