package com.lawrence.supportagent.feedback;

import com.lawrence.supportagent.feedback.port.AnswerFeedbackRepository;
import com.lawrence.supportagent.persistence.mapper.AnswerFeedbackMapper;
import com.lawrence.supportagent.persistence.record.AnswerFeedbackDO;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 使用 MyBatis 保存用户回答反馈，并通过唯一约束与版本条件保证并发安全。 */
@Repository
public class MySqlAnswerFeedbackRepository implements AnswerFeedbackRepository {
    private final AnswerFeedbackMapper mapper;

    /** 注入独立的反馈 Mapper。 */
    public MySqlAnswerFeedbackRepository(AnswerFeedbackMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override public boolean isSuccessfulOwnedRun(UUID runId, UUID userId) {
        return mapper.countSuccessfulOwnedRun(bytes(runId), bytes(userId)) == 1;
    }

    /** {@inheritDoc} */
    @Override public Optional<AnswerFeedback> find(UUID runId, UUID userId) {
        return Optional.ofNullable(mapper.find(bytes(runId), bytes(userId))).map(this::domain);
    }

    /** {@inheritDoc} */
    @Override public boolean insert(AnswerFeedback feedback) {
        try {
            return mapper.insert(record(feedback)) == 1;
        } catch (DuplicateKeyException exception) {
            return mapper.reactivate(record(feedback)) == 1;
        }
    }

    /** {@inheritDoc} */
    @Override public boolean update(AnswerFeedback feedback, long expectedVersion) {
        return mapper.update(record(feedback), expectedVersion) == 1;
    }

    /** {@inheritDoc} */
    @Override public boolean delete(UUID runId, UUID userId, long expectedVersion, java.time.Instant deletedAt) {
        return mapper.delete(bytes(runId), bytes(userId), expectedVersion, deletedAt) == 1;
    }

    /** {@inheritDoc} */
    @Transactional(readOnly = true)
    @Override public FeedbackSummary summary() {
        EnumMap<FeedbackRating, Long> ratings = new EnumMap<>(FeedbackRating.class);
        EnumMap<FeedbackReason, Long> reasons = new EnumMap<>(FeedbackReason.class);
        for (FeedbackRating rating : FeedbackRating.values()) ratings.put(rating, 0L);
        for (FeedbackReason reason : FeedbackReason.values()) reasons.put(reason, 0L);
        mapper.countByRating().forEach(row -> ratings.put(FeedbackRating.valueOf(row.category), row.count));
        mapper.countByReason().forEach(row -> reasons.put(FeedbackReason.valueOf(row.category), row.count));
        return new FeedbackSummary(mapper.countAll(), ratings, reasons);
    }

    /** 只转换反馈业务字段，不暴露 MyBatis 对象。 */
    private AnswerFeedback domain(AnswerFeedbackDO value) {
        return new AnswerFeedback(uuid(value.runId), uuid(value.userId),
                FeedbackRating.valueOf(value.rating),
                value.reason == null ? null : FeedbackReason.valueOf(value.reason),
                value.note, value.version, value.createdAt, value.updatedAt);
    }

    /** 生成数据库写入记录。 */
    private AnswerFeedbackDO record(AnswerFeedback value) {
        AnswerFeedbackDO row = new AnswerFeedbackDO();
        row.runId = bytes(value.runId());
        row.userId = bytes(value.userId());
        row.rating = value.rating().name();
        row.reason = value.reason() == null ? null : value.reason().name();
        row.note = value.note();
        row.version = value.version();
        row.createdAt = value.createdAt();
        row.updatedAt = value.updatedAt();
        return row;
    }

    /** 将公开 UUID 转成稳定的 BINARY(16) 字节序。 */
    private byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    /** 将 BINARY(16) 稳定还原为 UUID。 */
    private UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
