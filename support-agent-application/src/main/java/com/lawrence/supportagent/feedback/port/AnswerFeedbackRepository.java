package com.lawrence.supportagent.feedback.port;

import com.lawrence.supportagent.feedback.AnswerFeedback;
import com.lawrence.supportagent.feedback.FeedbackSummary;
import java.util.Optional;
import java.util.UUID;

/** 隔离成功运行归属查询和版本化反馈存储，不暴露 MyBatis 记录。 */
public interface AnswerFeedbackRepository {
    /** 仅在运行已成功且归属当前用户时返回真。 */
    boolean isSuccessfulOwnedRun(UUID runId, UUID userId);

    /** 按运行与用户查询当前反馈。 */
    Optional<AnswerFeedback> find(UUID runId, UUID userId);

    /** 首次写入或以递增版本重建已删除反馈；已有当前反馈时返回假。 */
    boolean insert(AnswerFeedback feedback);

    /** 按旧版本原子更正；版本已变化或已删除时返回假。 */
    boolean update(AnswerFeedback feedback, long expectedVersion);

    /** 按旧版本清空评分及说明并保留无内容版本标记；版本已变化时返回假。 */
    boolean delete(UUID runId, UUID userId, long expectedVersion, java.time.Instant deletedAt);

    /** 返回不含个体身份及正文的管理员聚合。 */
    FeedbackSummary summary();
}
