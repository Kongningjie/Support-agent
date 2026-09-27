package com.lawrence.supportagent.persistence.repository;

import com.lawrence.supportagent.knowledgespace.KnowledgeSpace;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceStatus;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceVisibility;
import com.lawrence.supportagent.knowledgespace.SpaceMembership;
import com.lawrence.supportagent.knowledgespace.SpaceMembershipStatus;
import com.lawrence.supportagent.knowledgespace.SpaceRole;
import com.lawrence.supportagent.knowledgespace.port.KnowledgeSpaceRepository;
import com.lawrence.supportagent.knowledgespace.port.SpaceMembershipRepository;
import com.lawrence.supportagent.persistence.mapper.KnowledgeSpaceMapper;
import com.lawrence.supportagent.persistence.record.KnowledgeSpaceDO;
import com.lawrence.supportagent.persistence.record.SpaceMembershipDO;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/** 使用 MyBatis XML 持久化知识空间与成员关系。 */
@Repository
public class KnowledgeSpaceMyBatisRepository
        implements KnowledgeSpaceRepository, SpaceMembershipRepository {
    private final KnowledgeSpaceMapper mapper;

    /** 注入知识空间 Mapper。 */
    public KnowledgeSpaceMyBatisRepository(KnowledgeSpaceMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<KnowledgeSpace> findBySpaceId(UUID spaceId) {
        return Optional.ofNullable(mapper.findBySpaceId(toBytes(spaceId))).map(this::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<KnowledgeSpace> findById(long id) {
        return Optional.ofNullable(mapper.findById(id)).map(this::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<KnowledgeSpace> findByCode(String code) {
        return Optional.ofNullable(mapper.findByCode(code)).map(this::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<KnowledgeSpace> findBySpaceIdForUpdate(UUID spaceId) {
        return Optional.ofNullable(mapper.findBySpaceIdForUpdate(toBytes(spaceId)))
                .map(this::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public KnowledgeSpace save(KnowledgeSpace space) {
        KnowledgeSpaceDO record = toRecord(space);
        int changed;
        try {
            changed = record.id == null ? mapper.insertSpace(record)
                    : mapper.updateSpace(record, space.version() - 1);
        } catch (DuplicateKeyException exception) {
            throw new ApplicationException(ErrorCode.KNOWLEDGE_SPACE_CODE_CONFLICT,
                    "空间代码已经存在");
        }
        if (changed != 1) {
            throw versionConflict();
        }
        return toDomain(record);
    }

    /** {@inheritDoc} */
    @Override
    public List<KnowledgeSpace> findReadable(UUID userId, int offset, int size) {
        return mapper.findReadable(toBytes(userId), offset, size).stream()
                .map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public long countReadable(UUID userId) {
        return mapper.countReadable(toBytes(userId));
    }

    /** {@inheritDoc} */
    @Override
    public List<KnowledgeSpace> findAdminPage(KnowledgeSpaceStatus status,
                                               KnowledgeSpaceVisibility visibility,
                                               String keyword, int offset, int size) {
        return mapper.findAdminPage(name(status), name(visibility), keyword, offset, size).stream()
                .map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public long countAdminPage(KnowledgeSpaceStatus status,
                               KnowledgeSpaceVisibility visibility, String keyword) {
        return mapper.countAdminPage(name(status), name(visibility), keyword);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<SpaceMembership> find(UUID userId, UUID spaceId) {
        return Optional.ofNullable(mapper.findMembership(toBytes(userId), toBytes(spaceId)))
                .map(this::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<SpaceMembership> findMembershipById(long id) {
        return Optional.ofNullable(mapper.findMembershipById(id)).map(this::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public SpaceMembership save(SpaceMembership membership) {
        SpaceMembershipDO record = toRecord(membership);
        int changed;
        try {
            changed = record.id == null ? mapper.insertMembership(record)
                    : mapper.updateMembership(record, membership.version() - 1);
        } catch (DuplicateKeyException exception) {
            throw new ApplicationException(ErrorCode.KNOWLEDGE_SPACE_MEMBERSHIP_CONFLICT,
                    "成员关系已经存在");
        }
        if (changed != 1) {
            throw versionConflict();
        }
        return toDomain(record);
    }

    /** {@inheritDoc} */
    @Override
    public List<SpaceMembership> findPage(UUID spaceId, int offset, int size) {
        return mapper.findMembershipPage(toBytes(spaceId), offset, size).stream()
                .map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public long countPage(UUID spaceId) {
        return mapper.countMembershipPage(toBytes(spaceId));
    }

    /** {@inheritDoc} */
    @Override
    public long countActiveManagers(UUID spaceId) {
        return mapper.countActiveManagers(toBytes(spaceId));
    }

    /** 把空间领域对象转换为 MyBatis 数据记录。 */
    private KnowledgeSpaceDO toRecord(KnowledgeSpace value) {
        KnowledgeSpaceDO record = new KnowledgeSpaceDO();
        record.id = value.id();
        record.spaceId = toBytes(value.spaceId());
        record.code = value.code();
        record.name = value.name();
        record.description = value.description();
        record.visibility = value.visibility().name();
        record.status = value.status().name();
        record.systemSpace = value.systemSpace();
        record.version = value.version();
        record.createdBy = value.createdBy();
        record.createdAt = value.createdAt();
        record.updatedBy = value.updatedBy();
        record.updatedAt = value.updatedAt();
        return record;
    }

    /** 把空间数据记录还原为领域对象。 */
    private KnowledgeSpace toDomain(KnowledgeSpaceDO value) {
        return new KnowledgeSpace(value.id, toUuid(value.spaceId), value.code, value.name,
                value.description, KnowledgeSpaceVisibility.valueOf(value.visibility),
                KnowledgeSpaceStatus.valueOf(value.status), value.systemSpace, value.version,
                value.createdBy, value.createdAt, value.updatedBy, value.updatedAt);
    }

    /** 把成员领域对象转换为 MyBatis 数据记录。 */
    private SpaceMembershipDO toRecord(SpaceMembership value) {
        SpaceMembershipDO record = new SpaceMembershipDO();
        record.id = value.id();
        record.userId = toBytes(value.userId());
        record.spaceId = toBytes(value.spaceId());
        record.role = value.role().name();
        record.status = value.status().name();
        record.version = value.version();
        record.createdBy = value.createdBy();
        record.createdAt = value.createdAt();
        record.updatedBy = value.updatedBy();
        record.updatedAt = value.updatedAt();
        record.revokedBy = value.revokedBy();
        record.revokedAt = value.revokedAt();
        return record;
    }

    /** 把成员数据记录还原为领域对象。 */
    private SpaceMembership toDomain(SpaceMembershipDO value) {
        return new SpaceMembership(value.id, toUuid(value.userId), toUuid(value.spaceId),
                SpaceRole.valueOf(value.role), SpaceMembershipStatus.valueOf(value.status),
                value.version, value.createdBy, value.createdAt, value.updatedBy,
                value.updatedAt, value.revokedBy, value.revokedAt);
    }

    /** 返回可空枚举名称供动态 SQL 使用。 */
    private String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /** 把 UUID 转为 MySQL BINARY(16)。 */
    private byte[] toBytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    /** 把 MySQL BINARY(16) 还原为 UUID。 */
    private UUID toUuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    /** 创建统一乐观锁冲突异常。 */
    private ApplicationException versionConflict() {
        return new ApplicationException(ErrorCode.KNOWLEDGE_SPACE_VERSION_CONFLICT,
                "空间或成员版本已变化");
    }
}
