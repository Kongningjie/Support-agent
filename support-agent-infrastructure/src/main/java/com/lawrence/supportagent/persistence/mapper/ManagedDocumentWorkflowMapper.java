package com.lawrence.supportagent.persistence.mapper;

import com.lawrence.supportagent.persistence.record.ManagedDocumentDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 提供托管文档分页、重复检测和条件状态更新 SQL。 */
@Mapper
public interface ManagedDocumentWorkflowMapper {
    /** 按状态、关键词和固定排序查询一页未删除文档。 */
    List<ManagedDocumentDO> findPage(@Param("status") String status,
                                     @Param("keyword") String keyword,
                                     @Param("offset") int offset,
                                     @Param("size") int size);

    /** 统计与分页条件一致的未删除文档数量。 */
    long count(@Param("status") String status, @Param("keyword") String keyword);

    /** 统计占用指定有效内容哈希且不是排除 ID 的文档。 */
    long countActiveHash(@Param("contentHash") String contentHash,
                         @Param("excludedId") Long excludedId);
}
