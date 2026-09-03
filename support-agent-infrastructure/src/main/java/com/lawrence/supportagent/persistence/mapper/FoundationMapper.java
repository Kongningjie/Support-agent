package com.lawrence.supportagent.persistence.mapper;

import com.lawrence.supportagent.persistence.record.AsyncTaskDO;
import com.lawrence.supportagent.persistence.record.ManagedDocumentDO;
import com.lawrence.supportagent.persistence.record.ResolvedCaseDO;
import com.lawrence.supportagent.persistence.record.TicketDO;
import org.apache.ibatis.annotations.Mapper;

/** 提供阶段一四类聚合的基础 MyBatis 持久化语句入口。 */
@Mapper
public interface FoundationMapper {
    /** 按内部主键读取工单。 */
    TicketDO findTicket(long id);

    /** 新增工单并回填内部主键。 */
    int insertTicket(TicketDO value);

    /** 按内部主键和原版本更新工单。 */
    int updateTicket(TicketDO value);

    /** 按内部主键读取托管文档。 */
    ManagedDocumentDO findDocument(long id);

    /** 新增托管文档并回填内部主键。 */
    int insertDocument(ManagedDocumentDO value);

    /** 按内部主键和原版本更新托管文档。 */
    int updateDocument(ManagedDocumentDO value);

    /** 按内部主键读取已解决案例。 */
    ResolvedCaseDO findCase(long id);

    /** 新增已解决案例并回填内部主键。 */
    int insertCase(ResolvedCaseDO value);

    /** 按内部主键和原版本更新已解决案例。 */
    int updateCase(ResolvedCaseDO value);

    /** 按内部主键读取异步任务。 */
    AsyncTaskDO findTask(long id);

    /** 新增异步任务并回填内部主键。 */
    int insertTask(AsyncTaskDO value);

    /** 按内部主键更新异步任务。 */
    int updateTask(AsyncTaskDO value);
}
