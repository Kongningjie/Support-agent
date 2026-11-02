package com.lawrence.supportagent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.chat.ChatExecutionControl.CancellationReason;
import com.lawrence.supportagent.user.UserRole;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 验证异步工作启动之前绑定回调，以及未启动运行和调度失败的唯一清理路径。 */
class ChatControllerLifecycleTest {
    /** 连接先断开时，晚返回的发送/心跳句柄立即停止，业务任务只释放占用。 */
    @Test
    void shouldBindCallbacksBeforeSchedulingAndCleanCancelledBeforeStart() {
        Fixture fixture = new Fixture();
        when(fixture.streams.submit(any(Runnable.class))).thenAnswer(invocation -> {
            fixture.emitter.assertCallbacksRegistered();
            fixture.emitter.error.accept(new IllegalStateException("synthetic disconnect"));
            return fixture.drain;
        });
        fixture.controller.stream(fixture.actor, fixture.request);
        fixture.business.get().run();
        verify(fixture.drain).cancel(true);
        verify(fixture.heartbeat).cancel(true);
        verify(fixture.useCase).failBeforeStart(fixture.prepared,
                CancellationReason.CHAT_CLIENT_DISCONNECTED);
        verify(fixture.useCase, never()).stream(eq(fixture.prepared), any());
    }

    /** 心跳回调超时保持专用原因，即使随后收到完成和错误回调也不覆盖。 */
    @Test
    void shouldKeepTimeoutReasonForUnstartedRun() {
        Fixture fixture = new Fixture();
        fixture.controller.stream(fixture.actor, fixture.request);
        fixture.emitter.timeout.run();
        fixture.emitter.completion.run();
        fixture.emitter.error.accept(new IllegalStateException("later error"));
        fixture.business.get().run();
        verify(fixture.useCase).failBeforeStart(fixture.prepared, CancellationReason.CHAT_RESPONSE_TIMEOUT);
        verify(fixture.drain).cancel(true);
    }

    /** 发送任务调度失败也必须释放 prepare 已经取得的运行权，不伪装成客户端取消。 */
    @Test
    void shouldReleaseRunWhenDrainSchedulingFails() {
        Fixture fixture = new Fixture();
        when(fixture.streams.submit(any(Runnable.class))).thenThrow(new RejectedExecutionException());
        assertThatThrownBy(() -> fixture.controller.stream(fixture.actor, fixture.request))
                .isInstanceOf(RejectedExecutionException.class);
        verify(fixture.useCase).failBeforeStart(fixture.prepared, null);
        verify(fixture.streams, never()).execute(any());
    }

    /** 心跳调度失败停止已提交的发送任务，不留下未执行聊天占用。 */
    @Test
    void shouldCleanDrainWhenHeartbeatSchedulingFails() {
        Fixture fixture = new Fixture();
        when(fixture.heartbeats.scheduleAtFixedRate(any(), eq(15L), eq(15L), eq(TimeUnit.SECONDS)))
                .thenThrow(new RejectedExecutionException());
        assertThatThrownBy(() -> fixture.controller.stream(fixture.actor, fixture.request))
                .isInstanceOf(RejectedExecutionException.class);
        verify(fixture.drain).cancel(true);
        verify(fixture.useCase).failBeforeStart(fixture.prepared, null);
        verify(fixture.streams, never()).execute(any());
    }

    /** 业务任务未被执行器接受时，两类资源都停止，且业务用例不会开始。 */
    @Test
    void shouldCleanAllResourcesWhenBusinessSchedulingFails() {
        Fixture fixture = new Fixture();
        doThrow(new RejectedExecutionException()).when(fixture.streams).execute(any());
        assertThatThrownBy(() -> fixture.controller.stream(fixture.actor, fixture.request))
                .isInstanceOf(RejectedExecutionException.class);
        verify(fixture.drain).cancel(true);
        verify(fixture.heartbeat).cancel(true);
        verify(fixture.useCase).failBeforeStart(fixture.prepared, null);
    }

    /** prepare 失败仍同步返回，不能创建响应或调度任务。 */
    @Test
    void shouldKeepPreparationFailureSynchronous() {
        Fixture fixture = new Fixture();
        when(fixture.useCase.prepare(any())).thenThrow(new IllegalStateException("权限拒绝"));
        assertThatThrownBy(() -> fixture.controller.stream(fixture.actor, fixture.request))
                .isInstanceOf(IllegalStateException.class).hasMessage("权限拒绝");
        verify(fixture.streams, never()).submit(any(Runnable.class));
        verify(fixture.useCase, never()).failBeforeStart(any(), any());
    }

    /** 正常执行交给原用例，不触发未启动清理。 */
    @Test
    void shouldRunPreparedBusinessNormally() {
        Fixture fixture = new Fixture();
        fixture.controller.stream(fixture.actor, fixture.request);
        fixture.business.get().run();
        verify(fixture.useCase).stream(eq(fixture.prepared), any());
        verify(fixture.useCase, never()).failBeforeStart(any(), any());
    }

    /** 无网络、无真实线程的可控调度夹具；通过捕获任务验证实际顺序。 */
    private static final class Fixture {
        private final ChatUseCase useCase = mock(ChatUseCase.class);
        private final ExecutorService streams = mock(ExecutorService.class);
        private final ScheduledExecutorService heartbeats = mock(ScheduledExecutorService.class);
        private final Future<?> drain = mock(Future.class);
        private final ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        private final AtomicReference<Runnable> business = new AtomicReference<>();
        private final CapturingEmitter emitter = new CapturingEmitter();
        private final ChatUseCase.PreparedChat prepared = mock(ChatUseCase.PreparedChat.class);
        private final AuthenticatedUser actor = new AuthenticatedUser(UUID.randomUUID(), "tester", UserRole.USER);
        private final ChatStreamRequest request = new ChatStreamRequest(UUID.randomUUID(), null,
                UUID.randomUUID(), "技术问题", null);
        private final ChatController controller = new ChatController(useCase, streams, heartbeats, () -> emitter);

        /** 默认捕获业务任务而不运行，发送任务也仅返回句柄。 */
        private Fixture() {
            when(useCase.prepare(any())).thenReturn(prepared);
            doReturn(drain).when(streams).submit(any(Runnable.class));
            doReturn(heartbeat).when(heartbeats)
                    .scheduleAtFixedRate(any(), eq(15L), eq(15L), eq(TimeUnit.SECONDS));
            doAnswer(invocation -> {
                emitter.assertCallbacksRegistered();
                business.set(invocation.getArgument(0));
                return null;
            }).when(streams).execute(any());
        }
    }

    /** 捕获真实控制器注册的回调，不使用反射访问 Spring 内部字段。 */
    private static final class CapturingEmitter extends SseEmitter {
        private Runnable completion;
        private Runnable timeout;
        private Consumer<Throwable> error;

        /** 保存完成回调供测试模拟容器事件。 */
        @Override public void onCompletion(Runnable callback) { completion = callback; }
        /** 保存超时回调供测试模拟容器事件。 */
        @Override public void onTimeout(Runnable callback) { timeout = callback; }
        /** 保存错误回调供测试模拟容器事件。 */
        @Override public void onError(Consumer<Throwable> callback) { error = callback; }
        /** 断言任何任务启动前都已绑定全部回调。 */
        private void assertCallbacksRegistered() {
            assertThat(completion).isNotNull();
            assertThat(timeout).isNotNull();
            assertThat(error).isNotNull();
        }
    }
}
