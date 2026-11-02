package com.lawrence.supportagent.agent.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import com.lawrence.supportagent.chat.ChatCancellationException;
import com.lawrence.supportagent.chat.ChatExecutionControl;
import io.agentscope.core.model.Model;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证工单 Agent 即使进入后续模型或工具入口，也不能越过取消围栏。 */
class CancellationAwareModelTest {
    /** 下一轮推理在实际订阅前检查，不能只在包装对象创建时检查。 */
    @Test void shouldFenceNextModelSubscription() {
        ChatExecutionControl control = new ChatExecutionControl();
        Model delegate = mock(Model.class);
        var pending = new CancellationAwareModel(delegate, control).stream(List.of(), List.of(), null);
        control.cancel(ChatExecutionControl.CancellationReason.CHAT_CLIENT_DISCONNECTED);
        assertThatThrownBy(pending::blockLast).isInstanceOf(ChatCancellationException.class);
        verifyNoInteractions(delegate);
    }

    /** 工具调用先检查取消，甚至不读取工单视图。 */
    @Test void shouldFenceTicketToolEntry() {
        ChatExecutionControl control = new ChatExecutionControl();
        var tool = new DashScopeChatModelAdapter.TicketLookupTool("T123456789012", null, control);
        control.cancel(ChatExecutionControl.CancellationReason.CHAT_RESPONSE_TIMEOUT);
        assertThatThrownBy(() -> tool.getTicket("T123456789012"))
                .isInstanceOf(ChatCancellationException.class);
    }
}
