package com.lawrence.supportagent.persistence.mapper;

import com.lawrence.supportagent.persistence.record.UserMemoryDO;
import com.lawrence.supportagent.persistence.record.UserMemorySettingsDO;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 提供长期记忆设置、分页、有效选择和乐观锁写入 SQL。 */
@Mapper
public interface UserMemoryMapper {
    /** 按所有者读取记忆设置。 */
    UserMemorySettingsDO findSettings(byte[] userId);
    /** 按内部主键读取记忆设置。 */
    UserMemorySettingsDO findSettingsById(long id);
    /** 锁定当前用户设置行，使候选数量检查和插入串行化。 */
    UserMemorySettingsDO findSettingsForUpdate(byte[] userId);
    /** 插入记忆设置并回填内部主键。 */
    int insertSettings(UserMemorySettingsDO settings);
    /** 按所有者与旧版本更新记忆设置。 */
    int updateSettings(@Param("settings") UserMemorySettingsDO settings,
                       @Param("expectedVersion") long expectedVersion);
    /** 返回用户当前记忆数量。 */
    long countByUser(byte[] userId);
    /** 在用户设置行锁保护下删除该用户达到保留期的待确认候选。 */
    int deleteExpiredProposedByUser(@Param("userId") byte[] userId,
                                    @Param("expiredBeforeOrAt") Instant expiredBeforeOrAt);
    /** 事务内跳过其他实例已锁定的行，领取一批达到保留期的候选主键。 */
    List<Long> findExpiredProposedIdsForUpdate(
            @Param("expiredBeforeOrAt") Instant expiredBeforeOrAt,
            @Param("limit") int limit);
    /** 按已经领取的内部主键批量永久删除候选。 */
    int deleteByIds(@Param("ids") List<Long> ids);
    /** 按所有者和可选状态统计分页总数。 */
    long countPage(@Param("userId") byte[] userId, @Param("status") String status);
    /** 按最近更新时间倒序分页查询本人记忆。 */
    List<UserMemoryDO> findPage(@Param("userId") byte[] userId,
                                @Param("status") String status,
                                @Param("offset") int offset, @Param("limit") int limit);
    /** 按所有者和公开 UUID 查询记忆。 */
    UserMemoryDO findByMemoryId(@Param("userId") byte[] userId,
                                @Param("memoryId") byte[] memoryId);
    /** 按所有者和内部主键查询记忆。 */
    UserMemoryDO findById(@Param("userId") byte[] userId, @Param("id") long id);
    /** 插入模型候选并回填内部主键。 */
    int insert(UserMemoryDO memory);
    /** 按所有者与旧版本更新记忆。 */
    int update(@Param("memory") UserMemoryDO memory,
               @Param("expectedVersion") long expectedVersion);
    /** 按所有者、公开 UUID 和旧版本永久删除记忆。 */
    int delete(@Param("userId") byte[] userId, @Param("memoryId") byte[] memoryId,
               @Param("expectedVersion") long expectedVersion);
    /** 按固定业务优先级读取未过期有效记忆。 */
    List<UserMemoryDO> findActive(@Param("userId") byte[] userId,
                                  @Param("now") Instant now, @Param("limit") int limit);
}
