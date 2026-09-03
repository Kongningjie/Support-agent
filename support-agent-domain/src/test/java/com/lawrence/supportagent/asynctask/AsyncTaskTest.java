package com.lawrence.supportagent.asynctask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 验证持久化异步任务的执行和重试状态。 */
class AsyncTaskTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    /** 验证首次失败进入等待重试并保留真实更新时间。 */
    @Test
    void shouldWaitForRetryAfterFirstFailure() {
        AsyncTask running = task().start("worker-1", NOW, NOW.plusSeconds(300));
        AsyncTask failed = running.fail("TEMPORARY", "临时故障", NOW.plusSeconds(30), NOW.plusSeconds(1));
        assertEquals(AsyncTaskStatus.RETRY_WAIT, failed.status());
        assertEquals(NOW.plusSeconds(1), failed.updatedAt());
    }

    /** 验证未运行任务不能直接标记成功。 */
    @Test
    void shouldRejectSucceedingPendingTask() {
        assertThrows(IllegalStateException.class, () -> task().succeed(NOW));
    }

    /** 验证等待重试任务可再次执行并最终成功。 */
    @Test
    void shouldRetryAndSucceed() {
        AsyncTask waiting = task().start("worker-1", NOW, NOW.plusSeconds(300))
                .fail("TEMPORARY", "临时故障", NOW.plusSeconds(30), NOW.plusSeconds(1));
        AsyncTask succeeded = waiting.start("worker-2", NOW.plusSeconds(30),
                        NOW.plusSeconds(330))
                .succeed(NOW.plusSeconds(31));

        assertEquals(AsyncTaskStatus.SUCCEEDED, succeeded.status());
        assertEquals(2, succeeded.attemptCount());
        assertEquals(NOW.plusSeconds(31), succeeded.finishedAt());
    }

    /** 验证最后一次失败进入死亡终态且不能重新执行。 */
    @Test
    void shouldBecomeDeadAfterLastAttempt() {
        AsyncTask lastAttempt = new AsyncTask(null, AsyncTaskType.KNOWLEDGE_INDEX,
                AggregateType.MANAGED_DOCUMENT, 1, 0, "knowledge:1:0",
                AsyncTaskStatus.RETRY_WAIT, 2, 3, NOW, null, null, null, null,
                null, null, "system", NOW, NOW, null, NOW)
                .start("worker-3", NOW, NOW.plusSeconds(300));
        AsyncTask dead = lastAttempt.fail("PERMANENT", "永久故障", NOW.plusSeconds(30),
                NOW.plusSeconds(1));

        assertEquals(AsyncTaskStatus.DEAD, dead.status());
        assertThrows(IllegalStateException.class,
                () -> dead.start("worker-4", NOW, NOW.plusSeconds(300)));
    }

    /** 验证待执行任务可以取消，终态任务不能再次取消。 */
    @Test
    void shouldCancelOnlyNonTerminalTask() {
        AsyncTask cancelled = task().cancel(NOW.plusSeconds(1));

        assertEquals(AsyncTaskStatus.CANCELLED, cancelled.status());
        assertThrows(IllegalStateException.class, () -> cancelled.cancel(NOW.plusSeconds(2)));
    }

    /** 验证尝试次数和必填幂等键必须满足约束。 */
    @Test
    void shouldRejectInvalidTaskFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new AsyncTask(null, AsyncTaskType.KNOWLEDGE_INDEX,
                        AggregateType.MANAGED_DOCUMENT, 1, 0, " ", AsyncTaskStatus.PENDING,
                        0, 3, NOW, null, null, null, null, null, null,
                        "system", NOW, null, null, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new AsyncTask(null, AsyncTaskType.KNOWLEDGE_INDEX,
                        AggregateType.MANAGED_DOCUMENT, 1, 0, "key", AsyncTaskStatus.PENDING,
                        4, 3, NOW, null, null, null, null, null, null,
                        "system", NOW, null, null, NOW));
    }

    /** 验证 Worker 标识、租约和下次重试时间必须形成有效执行窗口。 */
    @Test
    void shouldRejectInvalidExecutionWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> task().start(" ", NOW, NOW.plusSeconds(300)));
        assertThrows(IllegalArgumentException.class,
                () -> task().start("worker-1", NOW, NOW));

        AsyncTask running = task().start("worker-1", NOW, NOW.plusSeconds(300));
        assertThrows(IllegalArgumentException.class,
                () -> running.fail("TEMPORARY", "临时故障", NOW, NOW.plusSeconds(1)));
    }

    /** 创建测试所需的待执行任务。 */
    private AsyncTask task() {
        return new AsyncTask(null, AsyncTaskType.KNOWLEDGE_INDEX, AggregateType.MANAGED_DOCUMENT,
                1, 0, "knowledge:1:0", AsyncTaskStatus.PENDING, 0, 3, NOW,
                null, null, null, null, null, null, "system", NOW, null, null, NOW);
    }
}
