package com.lawrence.supportagent.persistence.mapper;

import com.lawrence.supportagent.persistence.record.HumanEvaluationCandidateDO;
import com.lawrence.supportagent.persistence.record.HumanEvaluationDatasetChangeDO;
import com.lawrence.supportagent.persistence.record.HumanEvaluationReviewDO;
import com.lawrence.supportagent.persistence.record.HumanEvaluationSampleDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 人工评测受限候选、样本、盲评和晋升审计 SQL 映射。 */
@Mapper
public interface HumanEvaluationMapper {
    /** 分页查询尚未建样本的无帮助反馈候选。 */
    List<HumanEvaluationCandidateDO> feedbackCandidates(@Param("offset") int offset, @Param("size") int size);

    /** 验证无帮助且成功运行的反馈来源。 */
    HumanEvaluationCandidateDO feedbackSource(@Param("runId") byte[] runId);

    /** 在创建反馈来源样本的事务中锁定有效反馈。 */
    HumanEvaluationCandidateDO lockFeedbackSource(@Param("runId") byte[] runId);

    /** 插入脱敏样本。 */
    int insertSample(HumanEvaluationSampleDO row);

    /** 分页读取样本。 */
    List<HumanEvaluationSampleDO> listSamples(@Param("offset") int offset, @Param("size") int size);

    /** 读取单份样本。 */
    HumanEvaluationSampleDO findSample(@Param("sampleId") byte[] sampleId);

    /** 事务内锁定样本以串行化评分或晋升。 */
    HumanEvaluationSampleDO lockSample(@Param("sampleId") byte[] sampleId);

    /** 读取不可变评分记录。 */
    List<HumanEvaluationReviewDO> reviews(@Param("sampleId") byte[] sampleId);

    /** 插入一次不可更改的评分。 */
    int insertReview(HumanEvaluationReviewDO row);

    /** 更新由评分结果计算的状态。 */
    int updateReviewStatus(@Param("sampleId") byte[] sampleId, @Param("status") String status);

    /** 插入数据集晋升审计并获取全局版本。 */
    int insertDatasetChange(HumanEvaluationDatasetChangeDO row);

    /** 把当前层级和版本原子写回样本。 */
    int updateDatasetState(@Param("sampleId") byte[] sampleId, @Param("state") String state,
                           @Param("version") long version);
}
