package com.lawrence.supportagent.asynctask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.asynctask.port.AsyncTaskRepository;
import com.lawrence.supportagent.idempotency.IdempotentExecutor;
import com.lawrence.supportagent.idempotency.IdempotencyCommand;
import com.lawrence.supportagent.idempotency.IdempotentResource;
import com.lawrence.supportagent.knowledge.port.ManagedDocumentRepository;
import com.lawrence.supportagent.resolvedcase.port.ResolvedCaseRepository;
import com.lawrence.supportagent.sharedkernel.OperatorId;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.ticket.Ticket;
import com.lawrence.supportagent.ticket.port.TicketRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 验证死亡任务人工重试时的原任务保留及关联业务版本门禁。 */
class AsyncTaskUseCaseTest {
    private static final Instant NOW = Instant.parse("2026-09-04T01:00:00Z");
    private AsyncTaskRepository taskRepository;
    private TicketRepository ticketRepository;
    private AsyncTaskUseCase useCase;

    /** 创建同步幂等执行器及四类仓储 Mock。 */
    @BeforeEach
    void setUp() {
        taskRepository = mock(AsyncTaskRepository.class);
        ticketRepository = mock(TicketRepository.class);
        IdempotentExecutor executor = new IdempotentExecutor() {
            /** {@inheritDoc} */
            @Override
            public <T> T execute(IdempotencyCommand command, Supplier<IdempotentResource<T>> action,
                                 LongFunction<T> replayLoader) {
                return action.get().value();
            }
        };
        useCase = new AsyncTaskUseCase(taskRepository, ticketRepository,
                mock(ManagedDocumentRepository.class), mock(ResolvedCaseRepository.class),
                executor, () -> new OperatorId("dev-operator"), () -> NOW);
    }

    /** 验证已解决工单同版本的死亡任务会生成独立待执行任务。 */
    @Test
    void shouldCreateNewRetryTaskForMatchingResolvedTicket() {
        Ticket resolved = resolvedTicket();
        AsyncTask dead = deadTask(resolved.id(), resolved.version());
        when(taskRepository.findById(10L)).thenReturn(Optional.of(dead));
        when(ticketRepository.findById(resolved.id())).thenReturn(Optional.of(resolved));
        when(taskRepository.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0), 11L));

        AsyncTaskDetails retried = useCase.retry(10L, "依赖已经恢复", "retry-1");

        assertEquals("11", retried.taskId());
        assertEquals("10", retried.retryOfTaskId());
        assertEquals(AsyncTaskStatus.PENDING, retried.status());
        assertEquals(resolved.version(), retried.aggregateVersion());
        assertNotEquals(dead.id().toString(), retried.taskId());
    }

    /** 验证关联工单版本已变化时返回稳定的不可重试错误。 */
    @Test
    void shouldRejectRetryWhenAggregateVersionChanged() {
        Ticket resolved = resolvedTicket();
        AsyncTask dead = deadTask(resolved.id(), resolved.version() - 1);
        when(taskRepository.findById(10L)).thenReturn(Optional.of(dead));
        when(ticketRepository.findById(resolved.id())).thenReturn(Optional.of(resolved));

        ApplicationException exception = assertThrows(ApplicationException.class,
                () -> useCase.retry(10L, "版本不匹配", "retry-2"));

        assertEquals(ErrorCode.ASYNC_TASK_NOT_RETRYABLE, exception.errorCode());
    }

    /** 创建带主键、编号并处于 RESOLVED 状态的测试工单。 */
    private Ticket resolvedTicket() {
        Ticket draft = new Ticket(20L, "T000000000020", null, null,
                "标题", "问题", null, com.lawrence.supportagent.ticket.TicketStatus.DRAFT,
                null, null, null, 0L, "tester", NOW, "tester", NOW,
                null, null, null, null);
        return draft.submit("tester", NOW).resolve("根因", "方案", "tester", NOW);
    }

    /** 创建指向给定工单版本的死亡案例生成任务。 */
    private AsyncTask deadTask(long ticketId, long version) {
        return new AsyncTask(10L, AsyncTaskType.CASE_GENERATION, AggregateType.TICKET,
                ticketId, version, "case-generation:20:2", AsyncTaskStatus.DEAD,
                3, 3, NOW, null, null, "FAILED", "失败", null, null,
                "system", NOW, NOW, NOW, NOW);
    }

    /** 为新建人工重试任务补入模拟数据库主键。 */
    private AsyncTask withId(AsyncTask task, long id) {
        return new AsyncTask(id, task.taskType(), task.aggregateType(), task.aggregateId(),
                task.aggregateVersion(), task.idempotencyKey(), task.status(),
                task.attemptCount(), task.maxAttempts(), task.nextRunAt(), task.lockedBy(),
                task.lockedUntil(), task.lastErrorCode(), task.lastErrorMessage(),
                task.retryOfTaskId(), task.manualRetryReason(), task.createdBy(),
                task.createdAt(), task.startedAt(), task.finishedAt(), task.updatedAt());
    }
}
