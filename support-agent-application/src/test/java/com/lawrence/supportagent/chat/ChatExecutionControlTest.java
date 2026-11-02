package com.lawrence.supportagent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.lawrence.supportagent.chat.ChatExecutionControl.CancellationReason;
import org.junit.jupiter.api.Test;

/** 验证连接终止标记只属于单次请求，保留第一次原因而不伪造下游停止状态。 */
class ChatExecutionControlTest {
    /** 未取消状态为空，不允许空原因制造不可判断的通知。 */
    @Test
    void shouldRequireReason() {
        ChatExecutionControl control = new ChatExecutionControl();
        assertThat(control.isCancelled()).isFalse();
        assertThat(control.cancellationReason()).isNull();
        assertThatNullPointerException().isThrownBy(() -> control.cancel(null));
    }

    /** 重复回调保留首次原因，不影响另一个聊天。 */
    @Test
    void shouldKeepFirstReasonAndIsolateRequests() {
        ChatExecutionControl control = new ChatExecutionControl();
        ChatExecutionControl other = new ChatExecutionControl();
        control.cancel(CancellationReason.CHAT_RESPONSE_TIMEOUT);
        control.cancel(CancellationReason.CHAT_CLIENT_DISCONNECTED);
        assertThat(control.isCancelled()).isTrue();
        assertThat(control.cancellationReason()).isEqualTo(CancellationReason.CHAT_RESPONSE_TIMEOUT);
        assertThat(other.isCancelled()).isFalse();
    }
}
