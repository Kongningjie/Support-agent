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
                System.getenv("DASHSCOPE_API_KEY"), modelName(), baseUrl());
        AtomicBoolean firstTextReceived = new AtomicBoolean();

        var answer = adapter.greeting("你好", List.of(), () -> firstTextReceived.set(true));

        assertThat(answer.text()).isNotBlank();
        assertThat(answer.promptVersion()).hasSize(12);
        assertThat(firstTextReceived).isTrue();
    }

    /** 验证当前 Chat 模型支持案例标题和问题的严格结构化输出。 */
    @Test
    void shouldGenerateStructuredResolvedCaseDraft() {
        DashScopeChatModelAdapter adapter = new DashScopeChatModelAdapter(
                System.getenv("DASHSCOPE_API_KEY"), modelName(), baseUrl());

        var draft = adapter.generateResolvedCaseDraft("""
                工单标题：MySQL 连接失败
                问题描述：应用连接 localhost:3307 时返回 Connection refused
                已尝试操作：已确认 MySQL 实际监听 3306
                """);

        assertThat(draft.title()).isNotBlank();
        assertThat(draft.problem()).isNotBlank();
    }

    /** 返回在线测试使用的显式 Chat 模型，默认与应用配置一致。 */
    private String modelName() {
        return System.getenv().getOrDefault("SUPPORT_AGENT_CHAT_MODEL",
                "qwen3.8-flash");
    }

    /** 返回在线环境显式地址，未配置时使用 DashScope 公共地址。 */
    private String baseUrl() {
        return System.getenv().getOrDefault("DASHSCOPE_HTTP_BASE_URL",
                "https://dashscope.aliyuncs.com/api/v1");
    }
}
