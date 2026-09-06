package com.lawrence.supportagent.persistence.mapper;

import com.lawrence.supportagent.persistence.record.IdempotencyRecordDO;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 提供外部幂等记录的短事务占用、锁定和结果更新 SQL。 */
@Mapper
public interface IdempotencyMapper {
    /** 删除指定唯一键下已超过保留期的旧记录。 */
    int deleteExpired(@Param("operatorId") String operatorId,
                      @Param("operationType") String operationType,
                      @Param("idempotencyKey") String idempotencyKey,
                      @Param("now") Instant now);

    /** 在唯一键尚不存在时插入 PROCESSING 记录。 */
    int insertIfAbsent(IdempotencyRecordDO record);

    /** 按唯一键加行锁读取当前幂等记录。 */
    IdempotencyRecordDO findForUpdate(@Param("operatorId") String operatorId,
                                      @Param("operationType") String operationType,
                                      @Param("idempotencyKey") String idempotencyKey);

    /** 按主键加行锁读取执行事务准备完成的记录。 */
    IdempotencyRecordDO findByIdForUpdate(long id);

    /** 把过期处理或可重试失败记录重新置为 PROCESSING。 */
    int reacquire(@Param("id") long id, @Param("lockedUntil") Instant lockedUntil,
                  @Param("updatedAt") Instant updatedAt);

    /** 仅在租约仍属于本次执行时保存成功资源。 */
    int markSucceeded(@Param("id") long id, @Param("expectedLockedUntil") Instant expectedLockedUntil,
                      @Param("resourceType") String resourceType, @Param("resourceId") long resourceId,
                      @Param("updatedAt") Instant updatedAt);

    /** 仅在租约仍属于本次执行时保存可重试或最终失败。 */
    int markFailed(@Param("id") long id, @Param("expectedLockedUntil") Instant expectedLockedUntil,
                   @Param("status") String status, @Param("responseCode") String responseCode,
                   @Param("failureMessage") String failureMessage, @Param("updatedAt") Instant updatedAt);
}
