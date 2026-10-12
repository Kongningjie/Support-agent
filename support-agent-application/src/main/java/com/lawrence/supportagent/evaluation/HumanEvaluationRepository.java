package com.lawrence.supportagent.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 隔离人工评测样本、盲评和数据集晋升的持久化原子操作。 */
public interface HumanEvaluationRepository {
    /** 查询无帮助反馈的受限候选元数据，不返回说明与回答。 */
    List<FeedbackCandidate> feedbackCandidates(int offset, int size);

    /** 校验某次运行仍是有效无帮助反馈，返回所属会话。 */
    Optional<UUID> feedbackConversation(UUID runId);

    /** 插入一份管理员确认的脱敏评测样本；来源重复时返回假。 */
    boolean create(Sample sample);

    /** 分页读取样本元数据和正文。 */
    List<Sample> list(int offset, int size);

    /** 按公开 ID 读取样本。 */
    Optional<Sample> find(UUID sampleId);

    /** 查询单个样本的评分；服务层负责执行盲评投影。 */
    List<Review> reviews(UUID sampleId);

    /** 在同一事务中锁定样本、拒绝同人重复并保存评分与最新状态。 */
    Sample submit(UUID sampleId, UUID reviewerId, Score score, String reason, Instant now);

    /** 在同一事务中校验状态并记录数据集晋升及版本。 */
    Sample promote(UUID sampleId, UUID actorId, DatasetState target, Instant now);

    /** 不含反馈说明或原始会话正文的候选信息。 */
    record FeedbackCandidate(UUID runId, UUID conversationId, String reason, Instant createdAt) { }

    /** 人工确认并存储的脱敏副本，status 为评审状态，datasetState 为数据集层级。 */
    record Sample(UUID id, String sourceType, String sourceRef, UUID spaceId, String question,
                  String answer, String evidence, UUID createdBy, ReviewStatus status,
                  DatasetState datasetState, long datasetVersion, Instant createdAt) { }

    /** 单人一次不可更改的五维盲评分。 */
    record Review(UUID reviewerId, String role, Score score, String reason, Instant createdAt) { }

    /** 五维 0～2 分的人工评分。 */
    record Score(int factual, int citation, int completeness, int actionability, int clarity) { }

    /** 双人一致或第三人复核前后的评审状态。 */
    enum ReviewStatus { FIRST_PENDING, SECOND_PENDING, ARBITRATION_PENDING, DECIDED }

    /** 独立人工评测集的晋升层级。 */
    enum DatasetState { NONE, DEVELOPMENT, REGRESSION }
}
