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
    private static final List<Duration> RETRY_DELAYS = List.of(
            Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10));
    private final AsyncTaskRepository repository;
    private final AsyncTaskCompletionPort completionPort;
    private final TimeProvider timeProvider;
    private final Map<AsyncTaskType, AsyncTaskHandler> handlers;

    /** 注入任务仓储、统一时间和可为空的生产 Handler 集合。 */
    public AsyncTaskRunner(AsyncTaskRepository repository, AsyncTaskCompletionPort completionPort,
                           TimeProvider timeProvider,
                           List<AsyncTaskHandler> handlers) {
        this.repository = repository;
        this.completionPort = completionPort;
        this.timeProvider = timeProvider;
        this.handlers = indexHandlers(handlers);
    }

    /** 执行单个已抢占任务；缺失 Handler 时直接记录不可重试死亡。 */
    public void run(AsyncTask task, String workerId) {
        AsyncTaskHandler handler = handlers.get(task.taskType());
        if (handler == null) {
            fail(task, workerId, null, new AsyncTaskExecutionException(
                    "ASYNC_TASK_HANDLER_MISSING", "当前任务类型未注册处理器", false));
            return;
        }
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

    /** 为仍由指定 Worker 持有的运行中任务续租五分钟。 */
    public boolean renewLease(long taskId, String workerId) {
        Instant now = timeProvider.now();
        return repository.renewLease(taskId, workerId, now.plus(Duration.ofMinutes(5)), now);
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

    /** 按从 1 开始的尝试次数返回冻结退避时长。 */
    private Duration retryDelay(int attemptCount) {
        int index = Math.max(0, Math.min(attemptCount - 1, RETRY_DELAYS.size() - 1));
        return RETRY_DELAYS.get(index);
    }

    /** 建立任务类型到唯一 Handler 的不可变索引并拒绝重复注册。 */
    private Map<AsyncTaskType, AsyncTaskHandler> indexHandlers(List<AsyncTaskHandler> values) {
        Map<AsyncTaskType, AsyncTaskHandler> indexed = new EnumMap<>(AsyncTaskType.class);
        if (values != null) {
            for (AsyncTaskHandler handler : values) {
                if (indexed.putIfAbsent(handler.taskType(), handler) != null) {
                    throw new IllegalArgumentException("同一异步任务类型不能注册多个处理器");
                }
            }
        }
        return Map.copyOf(indexed);
    }
}
