package com.lawrence.supportagent.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证 Prompt 模板变量允许名单与单次安全替换。 */
class PromptTemplateTest {
    /** 用户输入中的模板形状文本必须按原文保留，不能被当成二次变量展开。 */
    @Test
    void shouldNotExpandPlaceholderTextInsideUserValue() {
        PromptTemplate template = new PromptTemplate("/prompts/intent.md");

        String rendered = template.render(Map.of(
                "HISTORY", "历史正文",
                "MESSAGE", "如何输出 {{HISTORY}}？"));

        assertThat(rendered).contains("如何输出 {{HISTORY}}？");
    }

    /** 缺少或增加模板变量都必须拒绝。 */
    @Test
    void shouldRejectVariableSetMismatch() {
        PromptTemplate template = new PromptTemplate("/prompts/intent.md");

        assertThatThrownBy(() -> template.render(Map.of("MESSAGE", "你好")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
