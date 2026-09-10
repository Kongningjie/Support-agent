package com.lawrence.supportagent.evaluation;

/**
 * 保存一个 Chat 候选模型经过相同评测后的选择输入。
 *
 * @param modelName 精确模型名称
 * @param qualityPassed 首轮或允许的稳定性复测后是否通过全部质量硬门禁
 * @param firstRoundAllPassed 首轮是否通过全部质量硬门禁
 * @param stabilityRerunCount 因临时故障或首轮最终质量不合格执行的独立复测次数
 * @param completeLatencyP95Ms 完整回答 P95 毫秒数
 * @param totalTokens 本候选全部评测调用 Token 总量
 * @param uniqueQualityGain 是否相对 Flash 存在只能由该候选提供的质量收益
 */
public record AnswerModelCandidateResult(
        String modelName,
        boolean qualityPassed,
        boolean firstRoundAllPassed,
        int stabilityRerunCount,
        long completeLatencyP95Ms,
        long totalTokens,
        boolean uniqueQualityGain) { }
