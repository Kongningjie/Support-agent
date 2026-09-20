package com.lawrence.supportagent.persistence.mapper;

import com.lawrence.supportagent.persistence.record.UserAccountDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 提供本地用户的精确查询、插入和乐观锁更新 SQL。 */
@Mapper
public interface UserAccountMapper {
    /** 返回本地用户总数。 */
    long count();
    /** 按规范化用户名查询用户。 */
    UserAccountDO findByUsername(String username);
    /** 按内部主键查询用户。 */
    UserAccountDO findById(long id);
    /** 按公开 UUID 字节查询用户。 */
    UserAccountDO findByUserId(byte[] userId);
    /** 插入用户并回填内部主键。 */
    int insert(UserAccountDO user);
    /** 按旧版本更新状态和审计字段。 */
    int update(@Param("user") UserAccountDO user, @Param("expectedVersion") long expectedVersion);
}
