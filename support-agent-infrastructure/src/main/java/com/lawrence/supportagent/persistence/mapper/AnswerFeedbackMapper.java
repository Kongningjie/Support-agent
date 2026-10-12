package com.lawrence.supportagent.persistence.mapper;

import com.lawrence.supportagent.persistence.record.AnswerFeedbackDO;
import com.lawrence.supportagent.persistence.record.FeedbackCountDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 提供反馈归属校验、版本化写入和匿名聚合 SQL。 */
@Mapper
public interface AnswerFeedbackMapper {
    /** 仅查询匹配本人且标记成功的运行数量。 */
    int countSuccessfulOwnedRun(@Param("runId") byte[] runId, @Param("userId") byte[] userId);

    /** 查询本人当前反馈；不提供跨用户明细查询。 */
    AnswerFeedbackDO find(@Param("runId") byte[] runId, @Param("userId") byte[] userId);

    /** 插入第一次反馈，重复运行由唯一约束阻断。 */
    int insert(AnswerFeedbackDO record);

    /** 仅在既有行已删除时原子重建，并延续旧版本。 */
    int reactivate(AnswerFeedbackDO record);

    /** 按本人、运行和旧版本原子更新。 */
    int update(@Param("record") AnswerFeedbackDO record, @Param("expectedVersion") long expectedVersion);

    /** 按本人、运行和旧版本清空内容并写删除时间。 */
    int delete(@Param("runId") byte[] runId, @Param("userId") byte[] userId,
               @Param("expectedVersion") long expectedVersion,
               @Param("deletedAt") java.time.Instant deletedAt);

    /** 查询反馈总数。 */
    long countAll();

    /** 按帮助程度聚合，不返回明细。 */
    List<FeedbackCountDO> countByRating();

    /** 按无帮助原因聚合，不返回明细。 */
    List<FeedbackCountDO> countByReason();
}
