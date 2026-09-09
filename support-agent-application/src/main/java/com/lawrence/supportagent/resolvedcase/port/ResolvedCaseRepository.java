package com.lawrence.supportagent.resolvedcase.port;

import com.lawrence.supportagent.resolvedcase.ResolvedCase;
import com.lawrence.supportagent.resolvedcase.ResolvedCaseStatus;
import java.util.List;
import java.util.Optional;

/** 定义已解决案例聚合的持久化边界。 */
public interface ResolvedCaseRepository {
    /** 按内部主键查询案例。 */
    Optional<ResolvedCase> findById(long id);

    /** 按来源工单内部主键查询已经生成的唯一案例。 */
    Optional<ResolvedCase> findBySourceTicketId(long sourceTicketId);

    /** 新增或更新案例并返回持久化后的聚合。 */
    ResolvedCase save(ResolvedCase resolvedCase);

    /** 按状态、来源工单编号和关键词读取一页案例。 */
    List<ResolvedCase> findPage(ResolvedCaseStatus status, String sourceTicketNo,
                                String keyword, int offset, int size);

    /** 统计与分页条件一致的案例数量。 */
    long count(ResolvedCaseStatus status, String sourceTicketNo, String keyword);
}
