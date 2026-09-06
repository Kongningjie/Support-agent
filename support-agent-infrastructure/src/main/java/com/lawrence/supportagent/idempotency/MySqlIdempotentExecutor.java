package com.lawrence.supportagent.idempotency;

import com.lawrence.supportagent.persistence.mapper.IdempotencyMapper;
import com.lawrence.supportagent.persistence.record.IdempotencyRecordDO;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import java.time.Instant;
import java.util.Objects;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 使用 MySQL 唯一键、行锁和租约实现跨请求外部幂等协调。 */
@Repository
public class MySqlIdempotentExecutor implements IdempotentExecutor {
    private final IdempotencyMapper mapper;
    private final TimeProvider timeProvider;
    private final TransactionTemplate transactions;

    /** 注入幂等 Mapper、统一时间和 Spring 事务管理器。 */
    public MySqlIdempotentExecutor(IdempotencyMapper mapper, TimeProvider timeProvider,
                                   PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.timeProvider = timeProvider;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** {@inheritDoc} */
    @Override
    public <T> T execute(IdempotencyCommand command, Supplier<IdempotentResource<T>> action,
                         LongFunction<T> replayLoader) {
        Claim claim = Objects.requireNonNull(transactions.execute(status -> claim(command)));
        if (claim.replayResourceId != null) {
            return replayLoader.apply(claim.replayResourceId);
        }
        try {
            return Objects.requireNonNull(transactions.execute(status ->
                    executeFirst(claim, action)));
        } catch (ApplicationException exception) {
            recordFailure(claim, exception.errorCode(), exception.getMessage(),
                    exception.errorCode() == ErrorCode.DEPENDENCY_UNAVAILABLE);
            throw exception;
        } catch (RuntimeException exception) {
            recordFailure(claim, ErrorCode.COMMON_INTERNAL_ERROR,
                    "服务端暂时无法完成请求", true);
            throw exception;
        }
    }

    /** 在独立短事务中创建、复用或恢复幂等执行租约。 */
    private Claim claim(IdempotencyCommand command) {
        validate(command);
        Instant now = timeProvider.now();
        mapper.deleteExpired(command.operatorId(), command.operationType(),
                command.idempotencyKey(), now);
        IdempotencyRecordDO inserted = new IdempotencyRecordDO();
        inserted.operatorId = command.operatorId();
        inserted.operationType = command.operationType();
        inserted.idempotencyKey = command.idempotencyKey();
        inserted.requestHash = command.requestHash();
        inserted.status = IdempotencyStatus.PROCESSING.name();
        inserted.lockedUntil = now.plus(command.leaseDuration());
        inserted.createdAt = now;
        inserted.updatedAt = now;
        inserted.expiresAt = now.plus(command.retentionDuration());
        int created;
        try {
            created = mapper.insertIfAbsent(inserted);
        } catch (DuplicateKeyException exception) {
            created = 0;
        }
        IdempotencyRecordDO current = mapper.findForUpdate(command.operatorId(),
                command.operationType(), command.idempotencyKey());
        if (!command.requestHash().equals(current.requestHash)) {
            throw new ApplicationException(ErrorCode.COMMON_IDEMPOTENCY_KEY_REUSED,
                    "同一幂等键不能用于不同请求");
        }
        if (created == 1) {
            return new Claim(current.id, current.lockedUntil, null);
        }
        return claimExisting(current, now, command);
    }

    /** 根据已有记录状态返回重放、冲突或新的执行租约。 */
    private Claim claimExisting(IdempotencyRecordDO current, Instant now,
                                IdempotencyCommand command) {
        IdempotencyStatus status = IdempotencyStatus.valueOf(current.status);
        if (status == IdempotencyStatus.SUCCEEDED) {
            return new Claim(current.id, null, current.resourceId);
        }
        if (status == IdempotencyStatus.FAILED_FINAL) {
            throw recordedFailure(current);
        }
        if (status == IdempotencyStatus.PROCESSING && current.lockedUntil != null
                && current.lockedUntil.isAfter(now)) {
            throw new ApplicationException(ErrorCode.COMMON_IDEMPOTENCY_IN_PROGRESS,
                    "同一幂等操作仍在执行");
        }
        Instant newLease = now.plus(command.leaseDuration());
        if (mapper.reacquire(current.id, newLease, now) != 1) {
            throw new ApplicationException(ErrorCode.COMMON_IDEMPOTENCY_IN_PROGRESS,
                    "同一幂等操作状态已变化");
        }
        IdempotencyRecordDO reacquired = mapper.findByIdForUpdate(current.id);
        return new Claim(reacquired.id, reacquired.lockedUntil, null);
    }

    /** 在业务写入同一事务内校验租约并保存首次成功资源。 */
    private <T> T executeFirst(Claim claim, Supplier<IdempotentResource<T>> action) {
        IdempotentResource<T> result = action.get();
        if (result.resourceId() <= 0 || result.resourceType() == null
                || result.resourceType().isBlank()) {
            throw new IllegalStateException("幂等业务结果缺少资源定位信息");
        }
        if (mapper.markSucceeded(claim.recordId, claim.lockedUntil,
                result.resourceType(), result.resourceId(), timeProvider.now()) != 1) {
            throw new ApplicationException(ErrorCode.COMMON_IDEMPOTENCY_IN_PROGRESS,
                    "幂等执行租约已失效");
        }
        return result.value();
    }

    /** 在首次业务事务回滚后单独记录安全失败分类。 */
    private void recordFailure(Claim claim, ErrorCode errorCode,
                               String safeMessage, boolean retryable) {
        transactions.executeWithoutResult(status -> mapper.markFailed(claim.recordId,
                claim.lockedUntil, retryable ? IdempotencyStatus.FAILED_RETRYABLE.name()
                        : IdempotencyStatus.FAILED_FINAL.name(), errorCode.name(),
                safeMessage, timeProvider.now()));
    }

    /** 从最终失败记录恢复稳定应用异常，不暴露内部异常类型。 */
    private ApplicationException recordedFailure(IdempotencyRecordDO current) {
        ErrorCode errorCode;
        try {
            errorCode = ErrorCode.valueOf(current.responseCode);
        } catch (RuntimeException exception) {
            errorCode = ErrorCode.COMMON_INTERNAL_ERROR;
        }
        String message = current.failureMessage == null
                ? "首次请求已确定失败" : current.failureMessage;
        return new ApplicationException(errorCode, message);
    }

    /** 校验幂等命令中所有唯一键、哈希和时长字段。 */
    private void validate(IdempotencyCommand command) {
        if (command == null || command.operatorId() == null || command.operatorId().isBlank()
                || command.operatorId().length() > 100
                || command.operationType() == null || command.operationType().isBlank()
                || command.operationType().length() > 100
                || command.idempotencyKey() == null || command.idempotencyKey().isBlank()
                || command.idempotencyKey().length() > 160
                || command.requestHash() == null || !command.requestHash().matches("[0-9a-f]{64}")
                || command.leaseDuration() == null || command.leaseDuration().isNegative()
                || command.leaseDuration().isZero() || command.retentionDuration() == null
                || command.retentionDuration().compareTo(command.leaseDuration()) <= 0) {
            throw new IllegalArgumentException("幂等命令字段不满足约束");
        }
    }

    /** 保存一次幂等占用结果或成功重放资源 ID。 */
    private record Claim(long recordId, Instant lockedUntil, Long replayResourceId) {
    }
}
