package com.lawrence.supportagent.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 阶段 20 空间隔离评测聚合报告，不保存查询正文、知识正文或业务标识。
 *
 * @param runId 评测运行 UUID
 * @param generatedAt 报告生成 UTC 时间
 * @param totalCases 固定用例总数
 * @param crossSpaceRerankCandidates 跨空间进入 Rerank 的来源数量
 * @param crossSpaceReferences 跨空间引用数量
 * @param crossSpaceAnswerFacts 跨空间答案事实数量
 * @param sourceDetailLeaks 跨空间来源详情泄漏数量
 * @param permissionRejectionRate 权限变化新请求拒绝率
 * @param recallAtFive GLOBAL 加当前空间正确来源 Recall@5
 * @param lockedBaselineRecallAtFive 四期前锁定 Recall@5 基线
 * @param noHitAccuracy 无命中准确率
 * @param exactTermRecall 精确词召回率
 * @param enumerationLeaks 防枚举敏感信息命中数量
 * @param failedCaseIds 未达到预期的稳定用例编号
 * @param passed 是否达到全部冻结门槛
 */
public record KnowledgeSpaceEvaluationReport(
        UUID runId, Instant generatedAt, int totalCases,
        long crossSpaceRerankCandidates, long crossSpaceReferences,
        long crossSpaceAnswerFacts, long sourceDetailLeaks,
        double permissionRejectionRate, double recallAtFive,
        double lockedBaselineRecallAtFive, double noHitAccuracy,
        double exactTermRecall, long enumerationLeaks,
        List<String> failedCaseIds, boolean passed) {
    /** 冻结失败用例编号，确保写报告时聚合结果不可被调用方修改。 */
    public KnowledgeSpaceEvaluationReport {
        failedCaseIds = List.copyOf(failedCaseIds);
    }
}
