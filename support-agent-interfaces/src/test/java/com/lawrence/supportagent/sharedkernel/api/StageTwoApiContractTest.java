package com.lawrence.supportagent.sharedkernel.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lawrence.supportagent.asynctask.AsyncTaskController;
import com.lawrence.supportagent.ticket.TicketController;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 锁定阶段 2 对外响应不能泄露数据库主键或 Worker 租约字段。 */
class StageTwoApiContractTest {
    /** 验证工单只暴露业务编号，不暴露内部 id。 */
    @Test
    void shouldExposeTicketNumberWithoutInternalId() {
        Set<String> fields = componentNames(TicketController.TicketResponse.class);

        assertTrue(fields.contains("ticketNo"));
        assertFalse(fields.contains("id"));
    }

    /** 验证异步任务允许字符串 ID，但隐藏锁持有者和租约截止时间。 */
    @Test
    void shouldHideWorkerLeaseFields() {
        Set<String> fields = componentNames(AsyncTaskController.AsyncTaskResponse.class);

        assertTrue(fields.contains("taskId"));
        assertTrue(fields.contains("aggregateId"));
        assertFalse(fields.contains("lockedBy"));
        assertFalse(fields.contains("lockedUntil"));
    }

    /** 返回指定响应 Record 的字段名称集合。 */
    private Set<String> componentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName).collect(Collectors.toUnmodifiableSet());
    }
}
