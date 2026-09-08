package com.lawrence.supportagent.agent.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 仅在明确提供真实密钥时验证 AgentScope Chat 在线协议。 */
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
class DashScopeChatModelAdapterOnlineIT {
    /** 验证内部流式消费可得到完整回答并触发首个有效文本回调。 */
    @Test
    void shouldGenerateCompleteGreeting() {
        DashScopeChatModelAdapter adapter = new DashScopeChatModelAdapter(
                System.getenv("DASHSCOPE_API_KEY"), "qwen-plus", baseUrl());
        AtomicBoolean firstTextReceived = new AtomicBoolean();

        var answer = adapter.greeting("你好", List.of(), () -> firstTextReceived.set(true));

        assertThat(answer.text()).isNotBlank();
        assertThat(answer.promptVersion()).hasSize(12);
        assertThat(firstTextReceived).isTrue();
    }

    /** 返回在线环境显式地址，未配置时使用 DashScope 公共地址。 */
    private String baseUrl() {
        return System.getenv().getOrDefault("DASHSCOPE_HTTP_BASE_URL",
                "https://dashscope.aliyuncs.com/api/v1");
    }
}
