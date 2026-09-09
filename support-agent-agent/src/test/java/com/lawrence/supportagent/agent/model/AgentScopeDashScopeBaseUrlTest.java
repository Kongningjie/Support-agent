package com.lawrence.supportagent.agent.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** 验证 AgentScope DashScope Chat 服务地址的规范化规则。 */
class AgentScopeDashScopeBaseUrlTest {

    /** 配置为 DashScope 原生 {@code /api/v1} 地址时应转换为 AgentScope 所需主机地址。 */
    @Test
    void shouldRemoveApiV1Suffix() {
        assertEquals("https://example.com",
                AgentScopeDashScopeBaseUrl.normalize("https://example.com/api/v1"));
        assertEquals("https://example.com",
                AgentScopeDashScopeBaseUrl.normalize(" https://example.com/api/v1/ "));
    }

    /** 已经是主机地址时只移除末尾斜杠，不应改写其他路径。 */
    @Test
    void shouldKeepHostOrCustomPath() {
        assertEquals("https://example.com",
                AgentScopeDashScopeBaseUrl.normalize("https://example.com/"));
        assertEquals("https://example.com/gateway",
                AgentScopeDashScopeBaseUrl.normalize("https://example.com/gateway"));
    }

    /** 空地址应立即失败，避免在首次模型调用时才暴露配置错误。 */
    @Test
    void shouldRejectBlankBaseUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentScopeDashScopeBaseUrl.normalize(" "));
    }
}
