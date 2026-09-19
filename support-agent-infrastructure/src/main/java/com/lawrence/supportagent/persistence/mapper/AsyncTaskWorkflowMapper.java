package com.lawrence.supportagent.persistence.mapper;

import com.lawrence.supportagent.persistence.record.AsyncTaskDO;
import com.lawrence.supportagent.persistence.record.AsyncTaskMetricsDO;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 提供异步任务分页、跳锁抢占、续租和条件完成 SQL。 */
@Mapper
public interface AsyncTaskWorkflowMapper {
    /** 汇总 Outbox 积压、等待、重试、死亡任务和窗口吞吐指标。 */
    AsyncTaskMetricsDO metrics(@Param("throughputSince") Instant throughputSince);

    /** 按过滤条件和固定排序查询一页任务。 */
    List<AsyncTaskDO> findPage(@Param("taskType") String taskType,
                               @Param("status") String status,
                               @Param("aggregateType") String aggregateType,
                               @Param("aggregateId") Long aggregateId,
                               @Param("offset") int offset, @Param("size") int size);

    /** 统计与分页相同过滤条件下的任务总数。 */
    long count(@Param("taskType") String taskType, @Param("status") String status,
               @Param("aggregateType") String aggregateType,
               @Param("aggregateId") Long aggregateId);

    /** 按内部任务幂等键查询已经创建的任务。 */
    AsyncTaskDO findByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    /** 使用 FOR UPDATE SKIP LOCKED 选择当前事务可抢占的任务 ID。 */
    List<Long> findClaimableIds(@Param("now") Instant now, @Param("limit") int limit);

    /** 把一个已锁定任务切换或恢复为本 Worker 的 RUNNING 尝试。 */
    int claim(@Param("id") long id, @Param("workerId") String workerId,
              @Param("lockedUntil") Instant lockedUntil, @Param("now") Instant now);

    /** 为指定 Worker 当前持有的任务延长租约。 */
    int renewLease(@Param("id") long id, @Param("workerId") String workerId,
                   @Param("lockedUntil") Instant lockedUntil, @Param("now") Instant now);

    /** 把指定 Worker 当前持有的任务标记为成功。 */
    int complete(@Param("id") long id, @Param("workerId") String workerId,
                 @Param("now") Instant now);

    /** 把指定 Worker 当前持有的任务标记为重试等待或死亡。 */
    int fail(@Param("id") long id, @Param("workerId") String workerId,
             @Param("status") String status, @Param("nextRunAt") Instant nextRunAt,
             @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage,
             @Param("finishedAt") Instant finishedAt, @Param("now") Instant now);

    /** 取消尚未进入终态的指定任务。 */
    int cancel(@Param("id") long id, @Param("now") Instant now);

    /** 仅允许当前持锁 Worker 取消运行中任务。 */
    int cancelOwned(@Param("id") long id, @Param("workerId") String workerId,
                    @Param("now") Instant now);
}
