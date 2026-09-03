package com.lawrence.supportagent.persistence.record;

import com.lawrence.supportagent.asynctask.AggregateType;
import com.lawrence.supportagent.asynctask.AsyncTask;
import com.lawrence.supportagent.asynctask.AsyncTaskStatus;
import com.lawrence.supportagent.asynctask.AsyncTaskType;
import com.lawrence.supportagent.knowledge.DocumentInputType;
import com.lawrence.supportagent.knowledge.ManagedDocument;
import com.lawrence.supportagent.knowledge.ManagedDocumentStatus;
import com.lawrence.supportagent.resolvedcase.ResolvedCase;
import com.lawrence.supportagent.resolvedcase.ResolvedCaseStatus;
import com.lawrence.supportagent.ticket.Ticket;
import com.lawrence.supportagent.ticket.TicketStatus;
import java.nio.ByteBuffer;
import java.util.UUID;

/** 在领域聚合与 MyBatis 数据记录之间执行显式转换。 */
public final class AggregateRecordMapper {
    /** 禁止实例化无状态转换器。 */
    private AggregateRecordMapper() {
    }

    /**
     * 把工单聚合转换为数据记录。
     *
     * @param value 工单聚合
     * @return 可供 MyBatis 持久化的数据记录
     */
    public static TicketDO toRecord(Ticket value) {
        TicketDO record = new TicketDO();
        record.id = value.id();
        record.ticketNo = value.ticketNo();
        record.conversationId = toBytes(value.conversationId());
        record.sourceTurnId = toBytes(value.sourceTurnId());
        record.title = value.title();
        record.problemDescription = value.problemDescription();
        record.attemptedActions = value.attemptedActions();
        record.status = value.status().name();
        record.rootCause = value.rootCause();
        record.solution = value.solution();
        record.closeReason = value.closeReason();
        record.version = value.version();
        record.createdBy = value.createdBy();
        record.createdAt = value.createdAt();
        record.updatedBy = value.updatedBy();
        record.updatedAt = value.updatedAt();
        record.resolvedBy = value.resolvedBy();
        record.resolvedAt = value.resolvedAt();
        record.closedBy = value.closedBy();
        record.closedAt = value.closedAt();
        return record;
    }

    /**
     * 把工单数据记录还原为领域聚合。
     *
     * @param record 工单数据记录
     * @return 工单聚合
     */
    public static Ticket toDomain(TicketDO record) {
        return new Ticket(record.id, record.ticketNo, toUuid(record.conversationId),
                toUuid(record.sourceTurnId), record.title, record.problemDescription,
                record.attemptedActions, TicketStatus.valueOf(record.status), record.rootCause,
                record.solution, record.closeReason, record.version, record.createdBy,
                record.createdAt, record.updatedBy, record.updatedAt, record.resolvedBy,
                record.resolvedAt, record.closedBy, record.closedAt);
    }

    /**
     * 把托管文档聚合转换为数据记录。
     *
     * @param value 托管文档聚合
     * @return 可供 MyBatis 持久化的数据记录
     */
    public static ManagedDocumentDO toRecord(ManagedDocument value) {
        ManagedDocumentDO record = new ManagedDocumentDO();
        record.id = value.id();
        record.title = value.title();
        record.inputType = value.inputType().name();
        record.originalFileName = value.originalFileName();
        record.mediaType = value.mediaType();
        record.rawContent = value.rawContent();
        record.contentHash = value.contentHash();
        record.status = value.status().name();
        record.version = value.version();
        record.indexFailureReason = value.indexFailureReason();
        record.archiveReason = value.archiveReason();
        record.deleted = value.deleted();
        record.deletedBy = value.deletedBy();
        record.deletedAt = value.deletedAt();
        record.createdBy = value.createdBy();
        record.createdAt = value.createdAt();
        record.updatedBy = value.updatedBy();
        record.updatedAt = value.updatedAt();
        record.publishedBy = value.publishedBy();
        record.publishedAt = value.publishedAt();
        record.archivedBy = value.archivedBy();
        record.archivedAt = value.archivedAt();
        return record;
    }

    /**
     * 把托管文档数据记录还原为领域聚合。
     *
     * @param record 托管文档数据记录
     * @return 托管文档聚合
     */
    public static ManagedDocument toDomain(ManagedDocumentDO record) {
        return new ManagedDocument(record.id, record.title,
                DocumentInputType.valueOf(record.inputType), record.originalFileName,
                record.mediaType, record.rawContent, record.contentHash,
                ManagedDocumentStatus.valueOf(record.status), record.version,
                record.indexFailureReason, record.archiveReason, record.deleted,
                record.deletedBy, record.deletedAt, record.createdBy, record.createdAt,
                record.updatedBy, record.updatedAt, record.publishedBy, record.publishedAt,
                record.archivedBy, record.archivedAt);
    }

    /**
     * 把已解决案例聚合转换为数据记录。
     *
     * @param value 已解决案例聚合
     * @return 可供 MyBatis 持久化的数据记录
     */
    public static ResolvedCaseDO toRecord(ResolvedCase value) {
        ResolvedCaseDO record = new ResolvedCaseDO();
        record.id = value.id();
        record.sourceTicketId = value.sourceTicketId();
        record.title = value.title();
        record.problem = value.problem();
        record.cause = value.cause();
        record.solution = value.solution();
        record.status = value.status().name();
        record.contentHash = value.contentHash();
        record.version = value.version();
        record.publishFailureReason = value.publishFailureReason();
        record.rejectionReason = value.rejectionReason();
        record.archiveReason = value.archiveReason();
        record.deleted = value.deleted();
        record.deletedBy = value.deletedBy();
        record.deletedAt = value.deletedAt();
        record.createdBy = value.createdBy();
        record.createdAt = value.createdAt();
        record.updatedBy = value.updatedBy();
        record.updatedAt = value.updatedAt();
        record.publishedBy = value.publishedBy();
        record.publishedAt = value.publishedAt();
        record.archivedBy = value.archivedBy();
        record.archivedAt = value.archivedAt();
        return record;
    }

    /**
     * 把已解决案例数据记录还原为领域聚合。
     *
     * @param record 已解决案例数据记录
     * @return 已解决案例聚合
     */
    public static ResolvedCase toDomain(ResolvedCaseDO record) {
        return new ResolvedCase(record.id, record.sourceTicketId, record.title,
                record.problem, record.cause, record.solution,
                ResolvedCaseStatus.valueOf(record.status), record.contentHash, record.version,
                record.publishFailureReason, record.rejectionReason, record.archiveReason,
                record.deleted, record.deletedBy, record.deletedAt, record.createdBy,
                record.createdAt, record.updatedBy, record.updatedAt, record.publishedBy,
                record.publishedAt, record.archivedBy, record.archivedAt);
    }

    /**
     * 把异步任务聚合转换为数据记录。
     *
     * @param value 异步任务聚合
     * @return 可供 MyBatis 持久化的数据记录
     */
    public static AsyncTaskDO toRecord(AsyncTask value) {
        AsyncTaskDO record = new AsyncTaskDO();
        record.id = value.id();
        record.taskType = value.taskType().name();
        record.aggregateType = value.aggregateType().name();
        record.aggregateId = value.aggregateId();
        record.aggregateVersion = value.aggregateVersion();
        record.idempotencyKey = value.idempotencyKey();
        record.status = value.status().name();
        record.attemptCount = value.attemptCount();
        record.maxAttempts = value.maxAttempts();
        record.nextRunAt = value.nextRunAt();
        record.lockedBy = value.lockedBy();
        record.lockedUntil = value.lockedUntil();
        record.lastErrorCode = value.lastErrorCode();
        record.lastErrorMessage = value.lastErrorMessage();
        record.retryOfTaskId = value.retryOfTaskId();
        record.manualRetryReason = value.manualRetryReason();
        record.createdBy = value.createdBy();
        record.createdAt = value.createdAt();
        record.startedAt = value.startedAt();
        record.finishedAt = value.finishedAt();
        record.updatedAt = value.updatedAt();
        return record;
    }

    /**
     * 把异步任务数据记录还原为领域聚合。
     *
     * @param record 异步任务数据记录
     * @return 异步任务聚合
     */
    public static AsyncTask toDomain(AsyncTaskDO record) {
        return new AsyncTask(record.id, AsyncTaskType.valueOf(record.taskType),
                AggregateType.valueOf(record.aggregateType), record.aggregateId,
                record.aggregateVersion, record.idempotencyKey,
                AsyncTaskStatus.valueOf(record.status), record.attemptCount,
                record.maxAttempts, record.nextRunAt, record.lockedBy, record.lockedUntil,
                record.lastErrorCode, record.lastErrorMessage, record.retryOfTaskId,
                record.manualRetryReason, record.createdBy, record.createdAt,
                record.startedAt, record.finishedAt, record.updatedAt);
    }

    /**
     * 把 UUID 转为 MySQL BINARY(16)。
     *
     * @param value UUID；允许为空
     * @return 16 字节值；输入为空时返回空
     */
    private static byte[] toBytes(UUID value) {
        if (value == null) {
            return null;
        }
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    /**
     * 把 MySQL BINARY(16) 还原为 UUID。
     *
     * @param value 16 字节值；允许为空
     * @return UUID；输入为空时返回空
     */
    private static UUID toUuid(byte[] value) {
        if (value == null) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
