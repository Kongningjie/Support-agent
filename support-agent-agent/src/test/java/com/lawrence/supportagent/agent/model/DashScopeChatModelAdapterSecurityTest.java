package com.lawrence.supportagent.agent.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.lawrence.supportagent.model.ModelInvocationSecurity;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证随机标记和低基数重生成反馈只追加到系统指令。 */
class DashScopeChatModelAdapterSecurityTest {
    /** 安全上下文必须包含随机标记和规则编号，但不得包含失败正文。 */
    @Test
    void shouldAppendCanaryAndRuleCodesToSystemPrompt() {
        String result = DashScopeChatModelAdapter.secureSystemPrompt(
                "基础系统指令", new ModelInvocationSecurity(
                        "SA-CANARY-test", List.of("CITATION_REQUIRED")));

        assertThat(result).contains("基础系统指令", "SA-CANARY-test", "CITATION_REQUIRED",
                "不得复述失败正文");
    }

    /** 无安全上下文时应保持原 Prompt 原样，兼容非生产隔离测试。 */
    @Test
    void shouldKeepPromptWhenSecurityContextIsEmpty() {
        assertThat(DashScopeChatModelAdapter.secureSystemPrompt(
                "基础系统指令", ModelInvocationSecurity.none())).isEqualTo("基础系统指令");
    }
}
