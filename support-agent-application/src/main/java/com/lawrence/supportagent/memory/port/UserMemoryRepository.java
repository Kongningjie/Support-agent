package com.lawrence.supportagent.memory.port;

import com.lawrence.supportagent.memory.MemoryStatus;
import com.lawrence.supportagent.memory.UserMemory;
import com.lawrence.supportagent.memory.UserMemorySettings;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 隔离长期记忆与用户设置的 MySQL 持久化实现。 */
public interface UserMemoryRepository {
    /** 返回用户设置；尚未建行时返回空。 */
    Optional<UserMemorySettings> findSettings(UUID userId);
    /** 按内部主键读取设置，仅用于幂等重放。 */
    Optional<UserMemorySettings> findSettingsById(long id);
    /** 新增或按乐观锁更新用户设置。 */
    UserMemorySettings saveSettings(UserMemorySettings settings, long expectedVersion);
    /** 返回用户尚未永久删除的记忆数量。 */
    long countByUser(UUID userId);
    /** 分页查询用户自己的记忆，可按状态过滤。 */
    List<UserMemory> findPage(UUID userId, MemoryStatus status, int offset, int limit);
    /** 返回分页条件下的记录总数。 */
    long countPage(UUID userId, MemoryStatus status);
    /** 按公开记忆 UUID 和所有者读取聚合。 */
    Optional<UserMemory> findByMemoryId(UUID userId, UUID memoryId);
    /** 按内部主键和所有者读取聚合，仅用于幂等重放。 */
    Optional<UserMemory> findById(UUID userId, long id);
    /** 新增候选；同用户、类型和正文哈希已存在时返回空。 */
    Optional<UserMemory> insertCandidate(UserMemory memory);
    /** 按乐观锁保存更正、确认或撤销后的聚合。 */
    UserMemory update(UserMemory memory, long expectedVersion);
    /** 按所有者和版本永久删除单条记忆。 */
    long delete(UUID userId, UUID memoryId, long expectedVersion);
    /** 返回预算选择前的全部未过期有效记忆。 */
    List<UserMemory> findActive(UUID userId, Instant now, int limit);
}
