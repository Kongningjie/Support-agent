package com.lawrence.supportagent.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.lawrence.supportagent.model.IntentRecognitionPort;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证保守规则优先级与意图模型降级边界。 */
class IntentRecognitionServiceTest {
    /** 技术问题包含工单号时仍应进入支持检索，避免绕过知识链路。 */
    @Test
    void shouldPreferSupportForMixedTicketAndTechnicalQuestion() {
        IntentRecognitionPort model = (message, turns) -> new IntentDecision(
                ChatIntent.SUPPORT_QUERY, 0.95, message, null, "MODEL");
        IntentDecision result = new IntentRecognitionService(model, 0.70)
                .recognize("工单 T000000000001 的 MySQL 报错怎么处理", List.of());
        assertThat(result.intent()).isEqualTo(ChatIntent.SUPPORT_QUERY);
    }

    /** 模型失败必须保守降级为支持检索并使用当前消息作为独立问题。 */
    @Test
    void shouldFallbackToSupportWhenModelFails() {
        IntentRecognitionPort model = (message, turns) -> { throw new IllegalStateException("failed"); };
        IntentDecision result = new IntentRecognitionService(model, 0.70)
                .recognize("一个无法分类的问题", List.of());
        assertThat(result.intent()).isEqualTo(ChatIntent.SUPPORT_QUERY);
        assertThat(result.standaloneQuery()).isEqualTo("一个无法分类的问题");
    }

    /** 仅含有效工单号的查询应由确定性规则直接路由。 */
    @Test
    void shouldRoutePlainTicketNumberWithoutModel() {
        IntentRecognitionPort model = (message, turns) -> { throw new AssertionError("不应调用模型"); };
        IntentDecision result = new IntentRecognitionService(model, 0.70)
                .recognize("查询 T000000000001", List.of());
        assertThat(result.intent()).isEqualTo(ChatIntent.TICKET_QUERY);
        assertThat(result.ticketNo()).isEqualTo("T000000000001");
    }
}
