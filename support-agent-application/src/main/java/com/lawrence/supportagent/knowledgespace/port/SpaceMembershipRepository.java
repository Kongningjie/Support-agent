package com.lawrence.supportagent.knowledgespace.port;

import com.lawrence.supportagent.knowledgespace.SpaceMembership;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 隔离空间成员关系的 MySQL 持久化和分页查询。 */
public interface SpaceMembershipRepository {
    /** 按用户和空间读取唯一成员关系，包括已撤销记录。 */
    Optional<SpaceMembership> find(UUID userId, UUID spaceId);
    /** 按内部主键读取成员关系，仅用于幂等重放。 */
    Optional<SpaceMembership> findMembershipById(long id);
    /** 插入或按旧版本更新成员关系。 */
    SpaceMembership save(SpaceMembership membership);
    /** 按空间分页查询成员关系。 */
    List<SpaceMembership> findPage(UUID spaceId, int offset, int size);
    /** 返回空间内成员关系总数。 */
    long countPage(UUID spaceId);
    /** 返回空间内活动 MANAGER 数量，用于防止业务空间失去管理员。 */
    long countActiveManagers(UUID spaceId);
}
