package com.lawrence.supportagent.asynctask;

import com.lawrence.supportagent.asynctask.port.AsyncTaskRepository;
import com.lawrence.supportagent.asynctask.port.AsyncTaskCompletionPort;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 在事务外分派已抢占任务，并以稳定规则回写成功、重试或死亡状态。 */
public class AsyncTaskRunner {
    private static final List<Duration> DEFAULT_RETRY_DELAYS = List.of(
            Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10));
    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofMinutes(5);
    private final AsyncTaskRepository repository;
    private final AsyncTaskCompletionPort completionPort;
    private final TimeProvider timeProvider;
    private final Map<AsyncTaskType, List<AsyncTaskHandler>> handlers;
    private final List<Duration> retryDelays;
    private final Duration leaseDuration;

    /** 注入任务仓储、统一时间和可为空的生产 Handler 集合。 */
    public AsyncTaskRunner(AsyncTaskRepository repository, AsyncTaskCompletionPort completionPort,
                           TimeProvider timeProvider,
                           List<AsyncTaskHandler> handlers) {
        this(repository, completionPort, timeProvider, handlers,
                DEFAULT_RETRY_DELAYS, DEFAULT_LEASE_DURATION);
    }

    /** 注入任务依赖以及显式退避序列和续租时长。 */
    public AsyncTaskRunner(AsyncTaskRepository repository, AsyncTaskCompletionPort completionPort,
                           TimeProvider timeProvider, List<AsyncTaskHandler> handlers,
                           List<Duration> retryDelays, Duration leaseDuration) {
        validateOperationalSettings(retryDelays, leaseDuration);
        this.repository = repository;
        this.completionPort = completionPort;
        this.timeProvider = timeProvider;
        this.handlers = indexHandlers(handlers);
        this.retryDelays = List.copyOf(retryDelays);
        this.leaseDuration = leaseDuration;
    }

    /** 执行单个已抢占任务；缺失或路由冲突时直接记录不可重试死亡。 */
    public void run(AsyncTask task, String workerId) {
        List<AsyncTaskHandler> matches = handlers.getOrDefault(task.taskType(), List.of()).stream()
                .filter(candidate -> candidate.supports(task)).toList();
        if (matches.isEmpty()) {
            fail(task, workerId, null, new AsyncTaskExecutionException(
                    "ASYNC_TASK_HANDLER_MISSING", "当前任务类型未注册处理器", false));
            return;
        }
        if (matches.size() > 1) {
            fail(task, workerId, null, new AsyncTaskExecutionException(
                    "ASYNC_TASK_HANDLER_AMBIGUOUS", "当前任务匹配到多个处理器", false));
            return;
        }
        AsyncTaskHandler handler = matches.getFirst();
        if (task.attemptCount() >= task.maxAttempts()
                && "ASYNC_TASK_WORKER_LEASE_EXPIRED".equals(task.lastErrorCode())) {
            fail(task, workerId, handler, new AsyncTaskExecutionException(
                    "ASYNC_TASK_WORKER_LEASE_EXPIRED",
                    "Worker 租约过期且任务已耗尽尝试次数", false));
            return;
        }
        AsyncTaskExecutionContext context = new AsyncTaskExecutionContext(task,
                () -> renewLease(task.id(), workerId));
        AsyncTaskBusinessMutation successMutation;
        try {
            successMutation = handler.execute(context);
        } catch (AsyncTaskCancelledException exception) {
            completionPort.cancel(task.id(), workerId, timeProvider.now(),
                    exception.businessMutation());
            return;
        } catch (AsyncTaskExecutionException exception) {
            fail(task, workerId, handler, exception);
            return;
        } catch (RuntimeException exception) {
            fail(task, workerId, handler, new AsyncTaskExecutionException(
                    "ASYNC_TASK_UNEXPECTED_FAILURE", "异步任务执行失败", true));
            return;
        }
        completionPort.complete(task.id(), workerId, timeProvider.now(),
                successMutation == null ? AsyncTaskBusinessMutation.NONE : successMutation);
    }

    /** 按配置时长为仍由指定 Worker 持有的运行中任务续租。 */
    public boolean renewLease(long taskId, String workerId) {
        Instant now = timeProvider.now();
        return repository.renewLease(taskId, workerId, now.plus(leaseDuration), now);
    }

    /** 根据异常可重试性和当前尝试次数计算并保存失败状态。 */
    private void fail(AsyncTask task, String workerId, AsyncTaskHandler handler,
                      AsyncTaskExecutionException exception) {
        Instant now = timeProvider.now();
        AsyncTask failed = exception.retryable()
                ? task.fail(exception.errorCode(), exception.getMessage(),
                        now.plus(retryDelay(task.attemptCount())), now)
                : task.failPermanently(exception.errorCode(), exception.getMessage(), now);
        AsyncTaskBusinessMutation mutation = failed.status() == AsyncTaskStatus.DEAD && handler != null
                ? handler.finalFailureMutation(new AsyncTaskExecutionContext(task,
                        () -> renewLease(task.id(), workerId)), exception)
                : AsyncTaskBusinessMutation.NONE;
        completionPort.fail(task.id(), workerId, failed.status(), failed.nextRunAt(),
                failed.lastErrorCode(), failed.lastErrorMessage(), failed.finishedAt(), now, mutation);
    }

    /** 按从 1 开始的尝试次数返回配置退避时长，超出序列后沿用最后一项。 */
    private Duration retryDelay(int attemptCount) {
        int index = Math.max(0, Math.min(attemptCount - 1, retryDelays.size() - 1));
        return retryDelays.get(index);
    }

    /** 建立任务类型到按聚合继续路由的 Handler 不可变索引。 */
    private Map<AsyncTaskType, List<AsyncTaskHandler>> indexHandlers(List<AsyncTaskHandler> values) {
        Map<AsyncTaskType, List<AsyncTaskHandler>> indexed = new EnumMap<>(AsyncTaskType.class);
        if (values != null) {
            for (AsyncTaskHandler handler : values) {
                indexed.computeIfAbsent(handler.taskType(), ignored -> new java.util.ArrayList<>())
                        .add(handler);
            }
        }
        Map<AsyncTaskType, List<AsyncTaskHandler>> immutable = new EnumMap<>(AsyncTaskType.class);
        indexed.forEach((type, handlers) -> immutable.put(type, List.copyOf(handlers)));
        return Map.copyOf(immutable);
    }

    /** 校验任务退避和租约参数，防止零时长热循环或负时间。 */
    private void validateOperationalSettings(List<Duration> delays, Duration lease) {
        if (delays == null || delays.isEmpty() || delays.size() > 10
                || delays.stream().anyMatch(value -> value == null || value.isZero()
                        || value.isNegative())
                || lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("异步任务退避或租约配置不合法");
        }
    }
}
