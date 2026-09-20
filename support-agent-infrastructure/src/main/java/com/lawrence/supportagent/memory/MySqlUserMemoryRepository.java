package com.lawrence.supportagent.memory;

import com.lawrence.supportagent.memory.port.UserMemoryRepository;
import com.lawrence.supportagent.memory.CandidateInsertOutcome;
import com.lawrence.supportagent.memory.CandidateInsertResult;
import com.lawrence.supportagent.persistence.mapper.UserMemoryMapper;
import com.lawrence.supportagent.persistence.record.UserMemoryDO;
import com.lawrence.supportagent.persistence.record.UserMemorySettingsDO;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 使用 MyBatis 和 MySQL 持久化用户记忆设置与结构化长期记忆。 */
@Repository
public class MySqlUserMemoryRepository implements UserMemoryRepository {
    private final UserMemoryMapper mapper;

    /** 注入长期记忆 Mapper。 */
    public MySqlUserMemoryRepository(UserMemoryMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override public Optional<UserMemorySettings> findSettings(UUID userId) {
        return Optional.ofNullable(mapper.findSettings(toBytes(userId))).map(this::toSettings);
    }

    /** {@inheritDoc} */
    @Override public Optional<UserMemorySettings> findSettingsById(long id) {
        return Optional.ofNullable(mapper.findSettingsById(id)).map(this::toSettings);
    }

    /** {@inheritDoc} */
    @Override public UserMemorySettings saveSettings(UserMemorySettings settings, long expectedVersion) {
        UserMemorySettingsDO record = toRecord(settings);
        try {
            int changed = settings.id() == null ? mapper.insertSettings(record)
                    : mapper.updateSettings(record, expectedVersion);
            if (changed != 1) {
                throw versionConflict();
            }
            return toSettings(record);
        } catch (DuplicateKeyException exception) {
            throw versionConflict();
        }
    }

    /** {@inheritDoc} */
    @Override public long countByUser(UUID userId) {
        return mapper.countByUser(toBytes(userId));
    }

    /** {@inheritDoc} */
    @Override public List<UserMemory> findPage(UUID userId, MemoryStatus status, int offset, int limit) {
        return mapper.findPage(toBytes(userId), status == null ? null : status.name(), offset, limit)
                .stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override public long countPage(UUID userId, MemoryStatus status) {
        return mapper.countPage(toBytes(userId), status == null ? null : status.name());
    }

    /** {@inheritDoc} */
    @Override public Optional<UserMemory> findByMemoryId(UUID userId, UUID memoryId) {
        return Optional.ofNullable(mapper.findByMemoryId(toBytes(userId), toBytes(memoryId)))
                .map(this::toDomain);
    }

    /** {@inheritDoc} */
    @Override public Optional<UserMemory> findById(UUID userId, long id) {
        return Optional.ofNullable(mapper.findById(toBytes(userId), id)).map(this::toDomain);
    }

    /** {@inheritDoc} */
    @Transactional
    @Override public CandidateInsertResult insertCandidate(UserMemory memory,
                                                           Instant expiredBeforeOrAt) {
        UserMemorySettingsDO settings = mapper.findSettingsForUpdate(toBytes(memory.userId()));
        if (settings == null || !settings.enabled) {
            return CandidateInsertResult.rejected(CandidateInsertOutcome.DISABLED);
        }
        mapper.deleteExpiredProposedByUser(toBytes(memory.userId()), expiredBeforeOrAt);
        if (mapper.countByUser(toBytes(memory.userId())) >= 100) {
            return CandidateInsertResult.rejected(CandidateInsertOutcome.LIMIT_REACHED);
        }
        UserMemoryDO record = toRecord(memory);
        try {
            mapper.insert(record);
            return CandidateInsertResult.inserted(toDomain(record));
        } catch (DuplicateKeyException exception) {
            return CandidateInsertResult.rejected(CandidateInsertOutcome.DUPLICATE);
        }
    }

    /** {@inheritDoc} */
    @Transactional
    @Override public int cleanupExpiredProposed(Instant expiredBeforeOrAt, int batchSize) {
        List<Long> ids = mapper.findExpiredProposedIdsForUpdate(expiredBeforeOrAt, batchSize);
        return ids.isEmpty() ? 0 : mapper.deleteByIds(ids);
    }

    /** {@inheritDoc} */
    @Override public UserMemory update(UserMemory memory, long expectedVersion) {
        UserMemoryDO record = toRecord(memory);
        try {
            if (mapper.update(record, expectedVersion) != 1) {
                throw versionConflict();
            }
            return toDomain(record);
        } catch (DuplicateKeyException exception) {
            throw new ApplicationException(ErrorCode.MEMORY_DUPLICATE_CONTENT,
                    "同类型的相同长期记忆已经存在");
        }
    }

    /** {@inheritDoc} */
    @Override public long delete(UUID userId, UUID memoryId, long expectedVersion) {
        return mapper.delete(toBytes(userId), toBytes(memoryId), expectedVersion);
    }

    /** {@inheritDoc} */
    @Override public List<UserMemory> findActive(UUID userId, Instant now, int limit) {
        return mapper.findActive(toBytes(userId), now, limit).stream().map(this::toDomain).toList();
    }

    /** 将设置数据记录还原为应用对象。 */
    private UserMemorySettings toSettings(UserMemorySettingsDO value) {
        return new UserMemorySettings(value.id, toUuid(value.userId), value.enabled,
                value.version, value.createdAt, value.updatedAt);
    }

    /** 将设置应用对象转换为数据记录。 */
    private UserMemorySettingsDO toRecord(UserMemorySettings value) {
        UserMemorySettingsDO record = new UserMemorySettingsDO();
        record.id = value.id();
        record.userId = toBytes(value.userId());
        record.enabled = value.enabled();
        record.version = value.version();
        record.createdAt = value.createdAt();
        record.updatedAt = value.updatedAt();
        return record;
    }

    /** 将领域记忆转换为数据记录。 */
    private UserMemoryDO toRecord(UserMemory value) {
        UserMemoryDO record = new UserMemoryDO();
        record.id = value.id(); record.memoryId = toBytes(value.memoryId());
        record.userId = toBytes(value.userId()); record.memoryType = value.memoryType().name();
        record.content = value.content(); record.contentHash = value.contentHash();
        record.status = value.status().name(); record.pinned = value.pinned();
        record.sourceConversationId = toBytes(value.sourceConversationId());
        record.sourceTurnId = toBytes(value.sourceTurnId()); record.expiresAt = value.expiresAt();
        record.version = value.version(); record.createdBy = value.createdBy();
        record.createdAt = value.createdAt(); record.confirmedBy = value.confirmedBy();
        record.confirmedAt = value.confirmedAt(); record.updatedBy = value.updatedBy();
        record.updatedAt = value.updatedAt(); record.revokedBy = value.revokedBy();
        record.revokedAt = value.revokedAt();
        return record;
    }

    /** 将数据记录还原为领域记忆。 */
    private UserMemory toDomain(UserMemoryDO value) {
        return new UserMemory(value.id, toUuid(value.memoryId), toUuid(value.userId),
                MemoryType.valueOf(value.memoryType), value.content, value.contentHash,
                MemoryStatus.valueOf(value.status), value.pinned,
                toUuid(value.sourceConversationId), toUuid(value.sourceTurnId), value.expiresAt,
                value.version, value.createdBy, value.createdAt, value.confirmedBy,
                value.confirmedAt, value.updatedBy, value.updatedAt, value.revokedBy, value.revokedAt);
    }

    /** 把 UUID 编码为 MySQL BINARY(16)。 */
    private byte[] toBytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    /** 把 MySQL BINARY(16) 还原为 UUID。 */
    private UUID toUuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    /** 创建稳定长期记忆版本冲突。 */
    private ApplicationException versionConflict() {
        return new ApplicationException(ErrorCode.MEMORY_VERSION_CONFLICT, "长期记忆版本已变化");
    }
}
