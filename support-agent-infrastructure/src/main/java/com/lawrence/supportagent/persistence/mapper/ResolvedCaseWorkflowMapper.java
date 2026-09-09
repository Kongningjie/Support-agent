package com.lawrence.supportagent.persistence.mapper;

import com.lawrence.supportagent.persistence.record.ResolvedCaseDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 提供案例来源查询及受控分页 SQL。 */
@Mapper
public interface ResolvedCaseWorkflowMapper {
    /** 按来源工单读取唯一案例。 */
    ResolvedCaseDO findBySourceTicketId(long sourceTicketId);

    /** 按状态、来源工单号和关键词读取一页案例。 */
    List<ResolvedCaseDO> findPage(@Param("status") String status,
                                  @Param("sourceTicketNo") String sourceTicketNo,
                                  @Param("keyword") String keyword,
                                  @Param("offset") int offset, @Param("size") int size);

    /** 统计与分页相同条件的案例数量。 */
    long count(@Param("status") String status,
               @Param("sourceTicketNo") String sourceTicketNo,
               @Param("keyword") String keyword);
}
