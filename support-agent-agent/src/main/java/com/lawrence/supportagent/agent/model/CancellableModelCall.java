package com.lawrence.supportagent.agent.model;

import com.lawrence.supportagent.chat.ChatExecutionControl;
import java.time.Duration;
import java.util.function.Supplier;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** 将独立取消异常接入真实流订阅，取消不是空响应或普通完成。 */
final class CancellableModelCall {
    /** 工具类不创建实例。 */
    private CancellableModelCall() { }

    /** 延迟创建请求，注销动作并保留超时兜底；取消会终止上游订阅和本地等待。 */
    static <T> T awaitLast(Supplier<? extends Publisher<T>> source, ChatExecutionControl control,
                           Duration timeout) {
        control.checkCancelled();
        Sinks.One<Object> cancellation = Sinks.one();
        AtomicReference<Subscription> subscription = new AtomicReference<>();
        try (var registration = control.register(() -> {
            try {
                Subscription active = subscription.getAndSet(null);
                if (active != null) active.cancel();
            } finally {
                try { control.checkCancelled(); }
                catch (com.lawrence.supportagent.chat.ChatCancellationException exception) {
                    cancellation.tryEmitError(exception);
                }
            }
        })) {
            T result = Flux.defer(() -> {
                control.checkCancelled();
                return Flux.from(source.get());
            }).doOnSubscribe(active -> {
                subscription.set(active);
                if (control.isCancelled()) active.cancel();
            }).takeUntilOther(cancellation.asMono()).blockLast(timeout);
            control.checkCancelled();
            return result;
        } catch (RuntimeException exception) {
            control.checkCancelled();
            throw exception;
        } finally {
            Subscription active = subscription.getAndSet(null);
            if (active != null) active.cancel();
        }
    }
}
