package com.lawrence.supportagent.chat;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.chat.ChatExecutionControl.CancellationReason;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 暴露有界 SSE 聊天接口，在调度前绑定连接生命周期，避免丢失终止通知。 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
    private static final long HEARTBEAT_INTERVAL_SECONDS = 15;
    private static final Duration RESPONSE_TIMEOUT = Duration.ofMinutes(5);
    private static final System.Logger LOGGER = System.getLogger(ChatController.class.getName());
    private final ChatUseCase useCase;
    private final ExecutorService streams;
    private final ScheduledExecutorService heartbeats;
    private final Supplier<SseEmitter> emitters;

    /** 创建由接口层拥有的虚拟线程执行器与心跳调度器。 */
    @Autowired
    public ChatController(ChatUseCase useCase) {
        this(useCase, Executors.newVirtualThreadPerTaskExecutor(),
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().name("chat-heartbeat-", 0).factory()),
                () -> new SseEmitter(RESPONSE_TIMEOUT.toMillis()));
    }

    /** 注入可控的调度器及响应工厂，便于无网络验证调度失败和生命周期竞争。 */
    ChatController(ChatUseCase useCase, ExecutorService streams,
                   ScheduledExecutorService heartbeats, Supplier<SseEmitter> emitters) {
        this.useCase = useCase;
        this.streams = streams;
        this.heartbeats = heartbeats;
        this.emitters = emitters;
    }

    /** 同步完成认证/权限/运行准备；所有连接回调注册后才提交后台任务。 */
    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal AuthenticatedUser actor,
                             @Valid @RequestBody ChatStreamRequest request) {
        ChatUseCase.PreparedChat prepared = useCase.prepare(request.toCommand(actor));
        ChatExecutionControl control = new ChatExecutionControl();
        BoundedSseSink sink = null;
        try {
            SseEmitter emitter = emitters.get();
            sink = new BoundedSseSink(emitter, control);
            BoundedSseSink responseSink = sink;
            emitter.onCompletion(responseSink::onCompletion);
            emitter.onTimeout(() -> responseSink.abort(CancellationReason.CHAT_RESPONSE_TIMEOUT));
            emitter.onError(ignored -> responseSink.abort(CancellationReason.CHAT_CLIENT_DISCONNECTED));
            responseSink.registerResource(streams.submit(responseSink::drain));
            responseSink.registerResource(heartbeats.scheduleAtFixedRate(responseSink::heartbeat,
                    HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS));
            streams.execute(() -> runPrepared(prepared, responseSink, control));
            return emitter;
        } catch (RuntimeException exception) {
            CancellationReason reason = control.cancellationReason();
            if (sink != null) sink.abort(CancellationReason.CHAT_CLIENT_DISCONNECTED);
            releaseUnstarted(prepared, reason, control);
            throw exception;
        }
    }

    /** 未开始执行时终结占用并报告指标；已开始运行的取消由用例收尾。 */
    private void runPrepared(ChatUseCase.PreparedChat prepared, BoundedSseSink sink,
                             ChatExecutionControl control) {
        if (control.isCancelled()) {
            releaseUnstarted(prepared, control.cancellationReason(), control);
            return;
        }
        try {
            useCase.stream(prepared, sink, control);
        } catch (RuntimeException exception) {
            // 用例负责已启动运行的清理；这里仅兜底关闭传输，不推断提交结果。
            sink.abort(CancellationReason.CHAT_CLIENT_DISCONNECTED);
            LOGGER.log(System.Logger.Level.WARNING, "聊天执行异常退出，SSE 已关闭");
        } finally {
            sink.complete();
        }
    }

    /** 在非连接回调线程中释放未启动运行，失败只记录安全告警并保留租约兜底。 */
    private void releaseUnstarted(ChatUseCase.PreparedChat prepared, CancellationReason reason, ChatExecutionControl control) {
        try {
            useCase.failBeforeStart(prepared, reason, control);
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.ERROR, "未启动聊天运行清理失败，需按运行租约恢复");
        }
    }

    /** 关闭接口层拥有的执行器，先停止心跳调度以阻止新增后台活动。 */
    @PreDestroy
    public void close() {
        heartbeats.close();
        streams.close();
    }
}
