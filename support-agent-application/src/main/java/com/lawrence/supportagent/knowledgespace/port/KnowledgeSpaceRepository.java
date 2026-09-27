package com.lawrence.supportagent.knowledgespace.port;

import com.lawrence.supportagent.knowledgespace.KnowledgeSpace;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceStatus;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceVisibility;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 隔离知识空间 MySQL 持久化和授权范围分页查询。 */
public interface KnowledgeSpaceRepository {
    /** 按公开 UUID 查询空间。 */
    Optional<KnowledgeSpace> findBySpaceId(UUID spaceId);
    /** 按内部主键查询空间，仅用于幂等重放。 */
    Optional<KnowledgeSpace> findById(long id);
    /** 按稳定代码查询空间。 */
    Optional<KnowledgeSpace> findByCode(String code);
    /** 锁定空间行，序列化成员治理和最后管理员检查。 */
    Optional<KnowledgeSpace> findBySpaceIdForUpdate(UUID spaceId);
    /** 插入或按旧版本更新空间。 */
    KnowledgeSpace save(KnowledgeSpace space);
    /** 查询活动用户可读的活动空间，GLOBAL 固定排在最前。 */
    List<KnowledgeSpace> findReadable(UUID userId, int offset, int size);
    /** 返回活动用户可读的活动空间数量。 */
    long countReadable(UUID userId);
    /** 供平台管理员按受控条件分页查询全部空间。 */
    List<KnowledgeSpace> findAdminPage(KnowledgeSpaceStatus status,
                                       KnowledgeSpaceVisibility visibility,
                                       String keyword, int offset, int size);
    /** 返回平台管理员筛选条件下的空间数量。 */
    long countAdminPage(KnowledgeSpaceStatus status, KnowledgeSpaceVisibility visibility,
                        String keyword);
}
