package com.lawrence.supportagent.chat;

import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort.ChatStage;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort.UpstreamResult;

/** 保存单次聊天请求的首次连接取消原因，不依赖 Web 或模型框架。 */
public final class ChatExecutionControl {
    private final AtomicReference<CancellationReason> reason = new AtomicReference<>();
    private static final System.Logger LOGGER = System.getLogger(ChatExecutionControl.class.getName());
    private final List<Registration> registrations = new ArrayList<>();
    private boolean commitEntered;
    private volatile ChatStage stage = ChatStage.PREPARING;
    private volatile ChatStage cancelledStage = ChatStage.PREPARING;
    private volatile long cancellationStarted;
    private final AtomicBoolean cancellationReported = new AtomicBoolean();
    private final AtomicIntegerArray upstreamResults = new AtomicIntegerArray(UpstreamResult.values().length);

    /** 更新当前观测阶段，不干预提交获胜后的存储收尾。 */
    public void enterStage(ChatStage next) { stage = Objects.requireNonNull(next); }

    /** 返回首次检测取消时的固定业务阶段。 */
    public ChatStage cancellationStage() { return cancelledStage; }

    /** 计算从服务端检测取消到当前的耗时，不是用户关页面的时间。 */
    public long cancellationElapsedMillis() {
        return isCancelled() ? java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancellationStarted) : 0;
    }

    /** 每个请求仅允许报告一次收尾，重复生命周期回调不重复计数。 */
    public boolean claimCancellationReport() { return isCancelled() && cancellationReported.compareAndSet(false, true); }

    /** 累计当前调用的固定上游取消技术结果，不记录模型输入或输出。 */
    public void noteUpstreamCancellation(UpstreamResult result) { upstreamResults.incrementAndGet(result.ordinal()); }

    /** 返回固定上游技术动作次数；序号只作内存索引，不持久化。 */
    public int upstreamCancellationCount(UpstreamResult result) { return upstreamResults.get(result.ordinal()); }

    /** 与首次取消共用短锁竞争一次提交权；获胜后连接仍可取消发送，但不撤销提交权。 */
    public boolean tryEnterCommit() {
        synchronized (registrations) {
            if (commitEntered || isCancelled()) return false;
            commitEntered = true;
            return true;
        }
    }

    /** 首次取消生效，在锁外执行本请求的快速取消动作，不执行存储操作。 */
    public void cancel(CancellationReason cancellationReason) {
        Objects.requireNonNull(cancellationReason);
        List<Registration> active;
        synchronized (registrations) {
            if (reason.get() != null) return;
            cancellationStarted = System.nanoTime();
            cancelledStage = stage;
            reason.set(cancellationReason);
            active = List.copyOf(registrations);
            registrations.clear();
        }
        active.forEach(Registration::invoke);
    }

    /** 注册本次调用的取消动作；已取消时立即执行，关闭句柄后不再保留动作。 */
    public Registration register(Runnable action) {
        Registration registration = new Registration(Objects.requireNonNull(action));
        synchronized (registrations) {
            if (!isCancelled()) { registrations.add(registration); return registration; }
        }
        registration.invoke();
        return registration;
    }

    /** 已取消时抛出独立异常，不能当作模型故障进行重试或意图降级。 */
    public void checkCancelled() {
        CancellationReason current = reason.get();
        if (current != null) throw new ChatCancellationException(current);
    }

    /** 返回首次取消原因；尚未取消时为空。 */
    public CancellationReason cancellationReason() { return reason.get(); }

    /** 返回连接是否已经报告取消，不表示供应商推理已经停止。 */
    public boolean isCancelled() { return reason.get() != null; }

    /** 当前动作的可注销句柄；执行与注销竞争时最多执行一次。 */
    public final class Registration implements AutoCloseable {
        private final AtomicReference<Runnable> action;

        /** 保存单次动作，不持有模型框架类型。 */
        private Registration(Runnable action) { this.action = new AtomicReference<>(action); }

        /** 在控制器锁外执行；单个动作异常不阻断其他取消。 */
        private void invoke() {
            Runnable callback = action.getAndSet(null);
            if (callback == null) return;
            try { callback.run(); }
            catch (RuntimeException exception) {
                LOGGER.log(System.Logger.Level.WARNING, "聊天取消动作执行失败");
            }
        }

        /** 正常、异常或超时结束后幂等注销，避免响应闭包滞留。 */
        @Override public void close() {
            action.set(null);
            synchronized (registrations) { registrations.remove(this); }
        }
    }

    /** 固定连接终止原因，仅供内部清理和审计使用，不新增对外错误码。 */
    public enum CancellationReason {
        /** 连接异常或发送失败。 */ CHAT_CLIENT_DISCONNECTED,
        /** SSE 响应生命周期超时。 */ CHAT_RESPONSE_TIMEOUT,
        /** 有界队列或写入确认超过慢客户端保护期限。 */ CHAT_CLIENT_TOO_SLOW
    }
}
