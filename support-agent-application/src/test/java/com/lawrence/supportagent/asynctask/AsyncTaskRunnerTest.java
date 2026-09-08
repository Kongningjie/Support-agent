package com.lawrence.supportagent.asynctask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.asynctask.port.AsyncTaskRepository;
import com.lawrence.supportagent.asynctask.port.AsyncTaskCompletionPort;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 验证通用任务执行器的成功、退避和缺失 Handler 行为。 */
class AsyncTaskRunnerTest {
    private static final Instant NOW = Instant.parse("2026-09-04T01:00:00Z");

    /** 验证测试 Handler 成功后仅由当前 Worker 完成任务。 */
    @Test
    void shouldCompleteSuccessfulHandler() {
        AsyncTaskRepository repository = repository();
        AsyncTaskCompletionPort completionPort = mock(AsyncTaskCompletionPort.class);
        AsyncTaskHandler handler = handler(task -> { });
        AsyncTaskRunner runner = new AsyncTaskRunner(repository, completionPort, () -> NOW,
                List.of(handler));

        runner.run(runningTask(), "worker-1");

        verify(completionPort).complete(1L, "worker-1", NOW, AsyncTaskBusinessMutation.NONE);
    }

    /** 验证临时失败按第一次退避 30 秒进入等待重试。 */
    @Test
    void shouldRetryAfterTemporaryFailure() {
        AsyncTaskRepository repository = repository();
        AsyncTaskCompletionPort completionPort = mock(AsyncTaskCompletionPort.class);
        AsyncTaskHandler handler = handler(task -> {
            throw new AsyncTaskExecutionException("TEMPORARY", "临时失败", true);
        });
        AsyncTaskRunner runner = new AsyncTaskRunner(repository, completionPort, () -> NOW,
                List.of(handler));

        runner.run(runningTask(), "worker-1");

        ArgumentCaptor<Instant> nextRunAt = ArgumentCaptor.forClass(Instant.class);
        verify(completionPort).fail(eq(1L), eq("worker-1"), eq(AsyncTaskStatus.RETRY_WAIT),
                nextRunAt.capture(), eq("TEMPORARY"), eq("临时失败"), eq(null), eq(NOW),
                eq(AsyncTaskBusinessMutation.NONE));
        assertEquals(NOW.plusSeconds(30), nextRunAt.getValue());
    }

    /** 验证缺失生产 Handler 的任务直接进入死亡状态而不无限重试。 */
    @Test
    void shouldFailPermanentlyWhenHandlerIsMissing() {
        AsyncTaskRepository repository = repository();
        AsyncTaskCompletionPort completionPort = mock(AsyncTaskCompletionPort.class);
        AsyncTaskRunner runner = new AsyncTaskRunner(repository, completionPort, () -> NOW,
                List.of());

        runner.run(runningTask(), "worker-1");

        verify(completionPort).fail(1L, "worker-1", AsyncTaskStatus.DEAD, NOW,
                "ASYNC_TASK_HANDLER_MISSING", "当前任务类型未注册处理器", NOW, NOW,
                AsyncTaskBusinessMutation.NONE);
    }

    /** 验证处理器可按需延长当前 Worker 持有的任务租约。 */
    @Test
    void shouldExposeLeaseRenewalToHandler() {
        AsyncTaskRepository repository = repository();
        when(repository.renewLease(1L, "worker-1", NOW.plusSeconds(300), NOW)).thenReturn(true);
        AsyncTaskCompletionPort completionPort = mock(AsyncTaskCompletionPort.class);
        AsyncTaskHandler handler = handler(context -> context.renewLease());
        AsyncTaskRunner runner = new AsyncTaskRunner(repository, completionPort, () -> NOW,
                List.of(handler));

        runner.run(runningTask(), "worker-1");

        verify(repository).renewLease(1L, "worker-1", NOW.plusSeconds(300), NOW);
    }

    /** 验证最后一次执行崩溃后由新 Worker 通过统一事务边界收敛死亡状态。 */
    @Test
    void shouldFinalizeReclaimedExhaustedTaskWithoutExecutingAgain() {
        AsyncTaskRepository repository = repository();
        AsyncTaskCompletionPort completionPort = mock(AsyncTaskCompletionPort.class);
        AsyncTaskHandler handler = handler(context -> {
            throw new AssertionError("耗尽任务不能再次执行外部动作");
        });
        AsyncTaskRunner runner = new AsyncTaskRunner(repository, completionPort, () -> NOW,
                List.of(handler));
        AsyncTask exhausted = new AsyncTask(1L, AsyncTaskType.KNOWLEDGE_INDEX,
                AggregateType.MANAGED_DOCUMENT, 10L, 2L, "knowledge:10:2",
                AsyncTaskStatus.RUNNING, 3, 3, NOW, "worker-2", NOW.plusSeconds(300),
                "ASYNC_TASK_WORKER_LEASE_EXPIRED", "Worker 租约过期且任务已耗尽尝试次数",
                null, null, "system", NOW.minusSeconds(1), NOW, null, NOW);

        runner.run(exhausted, "worker-2");

        verify(completionPort).fail(1L, "worker-2", AsyncTaskStatus.DEAD, NOW,
                "ASYNC_TASK_WORKER_LEASE_EXPIRED", "Worker 租约过期且任务已耗尽尝试次数",
                NOW, NOW, AsyncTaskBusinessMutation.NONE);
    }

    /** 创建所有状态回写均成功的任务仓储 Mock。 */
    private AsyncTaskRepository repository() {
        AsyncTaskRepository repository = mock(AsyncTaskRepository.class);
        when(repository.complete(anyLong(), any(), any())).thenReturn(true);
        when(repository.fail(anyLong(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);
        return repository;
    }

    /** 创建支持知识索引类型的测试专用 Handler。 */
    private AsyncTaskHandler handler(java.util.function.Consumer<AsyncTaskExecutionContext> action) {
        return new AsyncTaskHandler() {
            /** {@inheritDoc} */
            @Override
            public AsyncTaskType taskType() {
                return AsyncTaskType.KNOWLEDGE_INDEX;
            }

            /** {@inheritDoc} */
            @Override
            public AsyncTaskBusinessMutation execute(AsyncTaskExecutionContext context) {
                action.accept(context);
                return AsyncTaskBusinessMutation.NONE;
            }
        };
    }

    /** 创建已经由 worker-1 抢占并开始首次尝试的任务。 */
    private AsyncTask runningTask() {
        return new AsyncTask(1L, AsyncTaskType.KNOWLEDGE_INDEX,
                AggregateType.MANAGED_DOCUMENT, 10L, 2L, "knowledge:10:2",
                AsyncTaskStatus.RUNNING, 1, 3, NOW, "worker-1", NOW.plusSeconds(300),
                null, null, null, null, "system", NOW.minusSeconds(1), NOW,
                null, NOW);
    }
}
