package com.lawrence.supportagent.persistence.mapper;

import com.lawrence.supportagent.persistence.record.KnowledgeSpaceDO;
import com.lawrence.supportagent.persistence.record.SpaceMembershipDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 提供知识空间、可见范围与成员关系的精确 SQL。 */
@Mapper
public interface KnowledgeSpaceMapper {
    /** 按公开 UUID 查询空间。 */
    KnowledgeSpaceDO findBySpaceId(byte[] spaceId);
    /** 按内部主键查询空间。 */
    KnowledgeSpaceDO findById(long id);
    /** 按稳定代码查询空间。 */
    KnowledgeSpaceDO findByCode(String code);
    /** 按公开 UUID 加排他锁查询空间。 */
    KnowledgeSpaceDO findBySpaceIdForUpdate(byte[] spaceId);
    /** 插入空间并回填内部主键。 */
    int insertSpace(KnowledgeSpaceDO space);
    /** 按旧版本更新普通空间。 */
    int updateSpace(@Param("space") KnowledgeSpaceDO space,
                    @Param("expectedVersion") long expectedVersion);
    /** 查询活动用户可读的活动空间。 */
    List<KnowledgeSpaceDO> findReadable(@Param("userId") byte[] userId,
                                        @Param("offset") int offset,
                                        @Param("size") int size);
    /** 统计活动用户可读的活动空间。 */
    long countReadable(byte[] userId);
    /** 管理员按可选条件分页查询全部空间。 */
    List<KnowledgeSpaceDO> findAdminPage(@Param("status") String status,
                                         @Param("visibility") String visibility,
                                         @Param("keyword") String keyword,
                                         @Param("offset") int offset,
                                         @Param("size") int size);
    /** 统计管理员筛选结果。 */
    long countAdminPage(@Param("status") String status,
                        @Param("visibility") String visibility,
                        @Param("keyword") String keyword);
    /** 按用户和空间读取唯一成员关系。 */
    SpaceMembershipDO findMembership(@Param("userId") byte[] userId,
                                     @Param("spaceId") byte[] spaceId);
    /** 按内部主键读取成员关系。 */
    SpaceMembershipDO findMembershipById(long id);
    /** 插入成员关系并回填内部主键。 */
    int insertMembership(SpaceMembershipDO membership);
    /** 按旧版本更新成员角色或状态。 */
    int updateMembership(@Param("membership") SpaceMembershipDO membership,
                         @Param("expectedVersion") long expectedVersion);
    /** 按空间分页查询全部成员关系。 */
    List<SpaceMembershipDO> findMembershipPage(@Param("spaceId") byte[] spaceId,
                                                @Param("offset") int offset,
                                                @Param("size") int size);
    /** 统计空间内全部成员关系。 */
    long countMembershipPage(byte[] spaceId);
    /** 统计空间内活动 MANAGER 数量。 */
    long countActiveManagers(byte[] spaceId);
}
