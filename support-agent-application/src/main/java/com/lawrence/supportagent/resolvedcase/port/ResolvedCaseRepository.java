package com.lawrence.supportagent.resolvedcase.port;

import com.lawrence.supportagent.resolvedcase.ResolvedCase;
import java.util.Optional;

/** 定义已解决案例聚合的持久化边界。 */
public interface ResolvedCaseRepository {
    /** 按内部主键查询案例。 */
    Optional<ResolvedCase> findById(long id);

    /** 新增或更新案例并返回持久化后的聚合。 */
    ResolvedCase save(ResolvedCase resolvedCase);
}
