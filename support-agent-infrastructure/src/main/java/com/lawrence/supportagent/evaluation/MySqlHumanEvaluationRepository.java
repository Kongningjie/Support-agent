package com.lawrence.supportagent.evaluation;

import com.lawrence.supportagent.evaluation.HumanEvaluationRepository.DatasetState;
import com.lawrence.supportagent.evaluation.HumanEvaluationRepository.FeedbackCandidate;
import com.lawrence.supportagent.evaluation.HumanEvaluationRepository.Review;
import com.lawrence.supportagent.evaluation.HumanEvaluationRepository.ReviewStatus;
import com.lawrence.supportagent.evaluation.HumanEvaluationRepository.Sample;
import com.lawrence.supportagent.evaluation.HumanEvaluationRepository.Score;
import com.lawrence.supportagent.persistence.mapper.HumanEvaluationMapper;
import com.lawrence.supportagent.persistence.record.HumanEvaluationCandidateDO;
import com.lawrence.supportagent.persistence.record.HumanEvaluationDatasetChangeDO;
import com.lawrence.supportagent.persistence.record.HumanEvaluationReviewDO;
import com.lawrence.supportagent.persistence.record.HumanEvaluationSampleDO;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** MyBatis 人工评测存储；行锁串行化评审与晋升并让失败事务完整回滚。 */
@Repository
public class MySqlHumanEvaluationRepository implements HumanEvaluationRepository {
    private final HumanEvaluationMapper mapper;

    /** 注入单一人工评测 Mapper。 */
    public MySqlHumanEvaluationRepository(HumanEvaluationMapper mapper) { this.mapper = mapper; }

    /** {@inheritDoc} */
    @Override public List<FeedbackCandidate> feedbackCandidates(int offset, int size) {
        return mapper.feedbackCandidates(offset, size).stream().map(this::candidate).toList();
    }

    /** {@inheritDoc} */
    @Override public Optional<UUID> feedbackConversation(UUID runId) {
        return Optional.ofNullable(mapper.feedbackSource(bytes(runId))).map(row -> uuid(row.conversationId));
    }

    /** {@inheritDoc} */
    @Transactional
    @Override public boolean create(Sample sample) {
        if ("FEEDBACK".equals(sample.sourceType()) && mapper.lockFeedbackSource(
                bytes(UUID.fromString(sample.sourceRef()))) == null) return false;
        try { return mapper.insertSample(record(sample)) == 1; }
        catch (DuplicateKeyException exception) { return false; }
    }

    /** {@inheritDoc} */
    @Override public List<Sample> list(int offset, int size) {
        return mapper.listSamples(offset, size).stream().map(this::sample).toList();
    }

    /** {@inheritDoc} */
    @Override public Optional<Sample> find(UUID sampleId) {
        return Optional.ofNullable(mapper.findSample(bytes(sampleId))).map(this::sample);
    }

    /** {@inheritDoc} */
    @Override public List<Review> reviews(UUID sampleId) {
        return mapper.reviews(bytes(sampleId)).stream().map(this::review).toList();
    }

    /** {@inheritDoc} */
    @Transactional
    @Override public Sample submit(UUID sampleId, UUID reviewerId, Score score, String reason, Instant now) {
        HumanEvaluationSampleDO locked = mapper.lockSample(bytes(sampleId));
        if (locked == null) throw invalid("评测样本不存在");
        ReviewStatus state = ReviewStatus.valueOf(locked.reviewStatus);
        if (state == ReviewStatus.DECIDED) throw conflict("评审已完成");
        List<HumanEvaluationReviewDO> existing = mapper.reviews(bytes(sampleId));
        if (existing.stream().anyMatch(row -> reviewerId.equals(uuid(row.reviewerId)))) {
            throw conflict("不能重复评分或兼任不同评审角色");
        }
        String role = switch (state) {
            case FIRST_PENDING -> "FIRST";
            case SECOND_PENDING -> "SECOND";
            case ARBITRATION_PENDING -> "ARBITER";
            case DECIDED -> throw conflict("评审已完成");
        };
        HumanEvaluationReviewDO row = new HumanEvaluationReviewDO();
        row.sampleId = bytes(sampleId);
        row.reviewerId = bytes(reviewerId);
        row.reviewRole = role;
        row.factual = score.factual();
        row.citation = score.citation();
        row.completeness = score.completeness();
        row.actionability = score.actionability();
        row.clarity = score.clarity();
        row.reason = reason;
        row.createdAt = now;
        if (mapper.insertReview(row) != 1) throw conflict("评分未保存");
        ReviewStatus next = switch (state) {
            case FIRST_PENDING -> ReviewStatus.SECOND_PENDING;
            case SECOND_PENDING -> same(existing.getFirst(), score)
                    ? ReviewStatus.DECIDED : ReviewStatus.ARBITRATION_PENDING;
            case ARBITRATION_PENDING -> ReviewStatus.DECIDED;
            case DECIDED -> throw conflict("评审已完成");
        };
        if (mapper.updateReviewStatus(bytes(sampleId), next.name()) != 1) throw conflict("评审状态未更新");
        return find(sampleId).orElseThrow();
    }

    /** {@inheritDoc} */
    @Transactional
    @Override public Sample promote(UUID sampleId, UUID actorId, DatasetState target, Instant now) {
        HumanEvaluationSampleDO locked = mapper.lockSample(bytes(sampleId));
        if (locked == null) throw invalid("评测样本不存在");
        if (ReviewStatus.valueOf(locked.reviewStatus) != ReviewStatus.DECIDED) {
            throw conflict("评审结论尚未形成");
        }
        DatasetState current = DatasetState.valueOf(locked.datasetState);
        if (!((current == DatasetState.NONE && target == DatasetState.DEVELOPMENT)
                || (current == DatasetState.DEVELOPMENT && target == DatasetState.REGRESSION))) {
            throw conflict("必须按开发集、回归集顺序分别审批");
        }
        HumanEvaluationDatasetChangeDO change = new HumanEvaluationDatasetChangeDO();
        change.sampleId = bytes(sampleId);
        change.actorId = bytes(actorId);
        change.fromState = current.name();
        change.toState = target.name();
        change.changedAt = now;
        if (mapper.insertDatasetChange(change) != 1 || change.version <= 0
                || mapper.updateDatasetState(bytes(sampleId), target.name(), change.version) != 1) {
            throw conflict("数据集晋升未完成");
        }
        return find(sampleId).orElseThrow();
    }

    /** 比较首评和次评分的全部维度，任何不同均进入第三人复核。 */
    private boolean same(HumanEvaluationReviewDO first, Score second) {
        return first.factual == second.factual() && first.citation == second.citation()
                && first.completeness == second.completeness()
                && first.actionability == second.actionability() && first.clarity == second.clarity();
    }

    /** 将受限 SQL 投影转换为公开候选。 */
    private FeedbackCandidate candidate(HumanEvaluationCandidateDO row) {
        return new FeedbackCandidate(uuid(row.runId), uuid(row.conversationId), row.reason, row.createdAt);
    }

    /** 将数据库样本转换为应用不可变模型。 */
    private Sample sample(HumanEvaluationSampleDO row) {
        return new Sample(uuid(row.sampleId), row.sourceType, row.sourceRef, uuid(row.spaceId),
                row.questionText, row.answerText, row.evidenceText, uuid(row.createdBy),
                ReviewStatus.valueOf(row.reviewStatus), DatasetState.valueOf(row.datasetState),
                row.datasetVersion, row.createdAt);
    }

    /** 将应用样本转换为数据库记录。 */
    private HumanEvaluationSampleDO record(Sample sample) {
        HumanEvaluationSampleDO row = new HumanEvaluationSampleDO();
        row.sampleId = bytes(sample.id());
        row.sourceType = sample.sourceType();
        row.sourceRef = sample.sourceRef();
        row.spaceId = bytes(sample.spaceId());
        row.questionText = sample.question();
        row.answerText = sample.answer();
        row.evidenceText = sample.evidence();
        row.createdBy = bytes(sample.createdBy());
        row.reviewStatus = sample.status().name();
        row.datasetState = sample.datasetState().name();
        row.datasetVersion = sample.datasetVersion();
        row.createdAt = sample.createdAt();
        return row;
    }

    /** 将数据库盲评分转换为应用不可变模型。 */
    private Review review(HumanEvaluationReviewDO row) {
        return new Review(uuid(row.reviewerId), row.reviewRole,
                new Score(row.factual, row.citation, row.completeness, row.actionability, row.clarity),
                row.reason, row.createdAt);
    }

    /** 用统一字节序编码 UUID。 */
    private byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    /** 用统一字节序还原 UUID。 */
    private UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    /** 返回稳定的业务冲突错误。 */
    private ApplicationException conflict(String message) {
        return new ApplicationException(ErrorCode.COMMON_CONFLICT, message);
    }

    /** 返回不包含来源或个人信息的输入错误。 */
    private ApplicationException invalid(String message) {
        return new ApplicationException(ErrorCode.COMMON_VALIDATION_FAILED, message);
    }
}
