package com.lawrence.supportagent.auth;

import com.lawrence.supportagent.auth.port.UserRepository;
import com.lawrence.supportagent.persistence.mapper.UserAccountMapper;
import com.lawrence.supportagent.persistence.record.UserAccountDO;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.user.UserAccount;
import com.lawrence.supportagent.user.UserRole;
import com.lawrence.supportagent.user.UserStatus;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/** 使用 MyBatis 和 MySQL 持久化本地用户。 */
@Repository
public class MySqlUserRepository implements UserRepository {
    private final UserAccountMapper mapper;

    /** 注入本地用户 Mapper。 */
    public MySqlUserRepository(UserAccountMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override public long count() {
        return mapper.count();
    }

    /** {@inheritDoc} */
    @Override public Optional<UserAccount> findByUsername(String username) {
        return Optional.ofNullable(mapper.findByUsername(username)).map(this::toDomain);
    }

    /** {@inheritDoc} */
    @Override public Optional<UserAccount> findById(long id) {
        return Optional.ofNullable(mapper.findById(id)).map(this::toDomain);
    }

    /** {@inheritDoc} */
    @Override public Optional<UserAccount> findByUserId(UUID userId) {
        return Optional.ofNullable(mapper.findByUserId(toBytes(userId))).map(this::toDomain);
    }

    /** {@inheritDoc} */
    @Override public UserAccount save(UserAccount account) {
        UserAccountDO record = toRecord(account);
        try {
            int changed = account.id() == null ? mapper.insert(record)
                    : mapper.update(record, account.version() - 1);
            if (changed != 1) {
                throw new ApplicationException(ErrorCode.AUTH_USER_VERSION_CONFLICT, "用户版本已变化");
            }
            return toDomain(record);
        } catch (DuplicateKeyException exception) {
            throw new ApplicationException(ErrorCode.AUTH_USERNAME_CONFLICT, "用户名已经存在");
        }
    }

    /** 将领域用户转换为 MyBatis 数据记录。 */
    private UserAccountDO toRecord(UserAccount value) {
        UserAccountDO record = new UserAccountDO();
        record.id = value.id(); record.userId = toBytes(value.userId());
        record.username = value.username(); record.displayName = value.displayName();
        record.passwordHash = value.passwordHash(); record.role = value.role().name();
        record.status = value.status().name(); record.version = value.version();
        record.passwordChangedAt = value.passwordChangedAt(); record.createdBy = value.createdBy();
        record.mustChangePassword = value.mustChangePassword(); record.lockedUntil = value.lockedUntil();
        record.createdAt = value.createdAt(); record.updatedBy = value.updatedBy();
        record.updatedAt = value.updatedAt();
        return record;
    }

    /** 将 MyBatis 数据记录还原为领域用户。 */
    private UserAccount toDomain(UserAccountDO value) {
        return new UserAccount(value.id, toUuid(value.userId), value.username, value.displayName,
                value.passwordHash, UserRole.valueOf(value.role), UserStatus.valueOf(value.status),
                value.version, value.passwordChangedAt, value.mustChangePassword, value.lockedUntil,
                value.createdBy, value.createdAt,
                value.updatedBy, value.updatedAt);
    }

    /** {@inheritDoc} */
    @Override public List<UserAccount> findPage(UserRole role, UserStatus status,
                                                int offset, int size) {
        return mapper.findPage(role == null ? null : role.name(),
                status == null ? null : status.name(), offset, size).stream()
                .map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override public long countPage(UserRole role, UserStatus status) {
        return mapper.countPage(role == null ? null : role.name(),
                status == null ? null : status.name());
    }

    /** {@inheritDoc} */
    @Override public void extendLock(UUID userId, java.time.Instant lockedUntil,
                                     String operator, java.time.Instant now) {
        mapper.extendLock(toBytes(userId), lockedUntil, operator, now);
    }

    /** {@inheritDoc} */
    @Override public void clearExpiredLock(UUID userId, java.time.Instant now, String operator) {
        mapper.clearExpiredLock(toBytes(userId), now, operator);
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
}
