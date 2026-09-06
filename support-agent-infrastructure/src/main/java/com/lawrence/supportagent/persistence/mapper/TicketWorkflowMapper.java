package com.lawrence.supportagent.persistence.mapper;

import com.lawrence.supportagent.persistence.record.TicketDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 提供工单编号、公开查询和受控分页所需的 MyBatis SQL。 */
@Mapper
public interface TicketWorkflowMapper {
    /** 按稳定公开编号读取工单。 */
    TicketDO findByTicketNo(String ticketNo);

    /** 仅为尚未编号的指定工单分配一次编号。 */
    int assignNumber(@Param("id") long id, @Param("ticketNo") String ticketNo);

    /** 按状态、关键词及固定排序读取一页工单。 */
    List<TicketDO> findPage(@Param("status") String status, @Param("keyword") String keyword,
                            @Param("offset") int offset, @Param("size") int size);

    /** 统计与分页相同过滤条件下的工单数量。 */
    long count(@Param("status") String status, @Param("keyword") String keyword);
}
