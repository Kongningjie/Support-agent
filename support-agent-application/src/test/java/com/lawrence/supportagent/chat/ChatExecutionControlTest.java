package com.lawrence.supportagent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.lawrence.supportagent.chat.ChatExecutionControl.CancellationReason;
import org.junit.jupiter.api.Test;

/** 验证连接终止标记只属于单次请求，保留第一次原因而不伪造下游停止状态。 */
class ChatExecutionControlTest {
    /** 取消先取得执行权时永远不能再进入提交。 */
    @Test void shouldRejectCommitAfterCancellation() {
        ChatExecutionControl control = new ChatExecutionControl();
        control.cancel(CancellationReason.CHAT_CLIENT_DISCONNECTED);
        assertThat(control.tryEnterCommit()).isFalse();
    }

    /** 提交只能进入一次；提交获胜不阻止连接继续报告取消。 */
    @Test void shouldKeepCommitOwnershipAfterDisconnect() {
        ChatExecutionControl control = new ChatExecutionControl();
        assertThat(control.tryEnterCommit()).isTrue();
        control.cancel(CancellationReason.CHAT_CLIENT_DISCONNECTED);
        assertThat(control.isCancelled()).isTrue();
        assertThat(control.tryEnterCommit()).isFalse();
    }

    /** 同时竞争时结果必须符合锁内线性化顺序，取消先完成绝不能进入提交。 */
    @Test void shouldLinearizeCommitAndCancellation() throws Exception {
        try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int attempt = 0; attempt < 100; attempt++) {
                ChatExecutionControl control = new ChatExecutionControl();
                var ready = new java.util.concurrent.CountDownLatch(1);
                var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
                var commit = threads.submit(() -> {
                    ready.await();
                    boolean cancellationAlreadyReturned = cancelled.get();
                    boolean entered = control.tryEnterCommit();
                    assertThat(cancellationAlreadyReturned && entered).isFalse();
                    return entered;
                });
                var cancel = threads.submit(() -> {
                    ready.await();
                    control.cancel(CancellationReason.CHAT_RESPONSE_TIMEOUT);
                    cancelled.set(true);
                    return null;
                });
                ready.countDown();
                commit.get(1, java.util.concurrent.TimeUnit.SECONDS);
                cancel.get(1, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(control.tryEnterCommit()).isFalse();
            }
        }
    }
    /** 先取消后注册也执行一次，重复通知和关闭不能重复执行。 */
    @Test
    void shouldInvokeLateRegistrationOnce() {
        ChatExecutionControl control = new ChatExecutionControl();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        control.cancel(ChatExecutionControl.CancellationReason.CHAT_CLIENT_DISCONNECTED);
        try (var registration = control.register(calls::incrementAndGet)) {
            control.cancel(ChatExecutionControl.CancellationReason.CHAT_RESPONSE_TIMEOUT);
        }
        assertThat(calls.get()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(control::checkCancelled)
                .isInstanceOf(ChatCancellationException.class);
    }

    /** 正常结束注销后，连接后续关闭不得调用过期动作。 */
    @Test
    void shouldUnregisterAndIsolateCallbackFailure() {
        ChatExecutionControl control = new ChatExecutionControl();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var old = control.register(() -> calls.addAndGet(100));
        old.close(); old.close();
        control.register(() -> { throw new IllegalStateException("synthetic failure"); });
        control.register(calls::incrementAndGet);
        control.cancel(ChatExecutionControl.CancellationReason.CHAT_RESPONSE_TIMEOUT);
        assertThat(calls.get()).isEqualTo(1);
    }

    /** 并发注册与取消不能遗漏通知，且每个动作最多一次。 */
    @Test
    void shouldNotLoseConcurrentRegistration() throws Exception {
        try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int attempt = 0; attempt < 100; attempt++) {
                ChatExecutionControl control = new ChatExecutionControl();
                var calls = new java.util.concurrent.atomic.AtomicInteger();
                var ready = new java.util.concurrent.CountDownLatch(1);
                var register = threads.submit(() -> { ready.await(); return control.register(calls::incrementAndGet); });
                var cancel = threads.submit(() -> { ready.await(); control.cancel(CancellationReason.CHAT_RESPONSE_TIMEOUT); return null; });
                ready.countDown();
                var registration = register.get(1, java.util.concurrent.TimeUnit.SECONDS);
                cancel.get(1, java.util.concurrent.TimeUnit.SECONDS);
                registration.close();
                assertThat(calls.get()).isEqualTo(1);
            }
        }
    }
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
