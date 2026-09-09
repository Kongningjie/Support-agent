package com.lawrence.supportagent.evaluation;

import com.lawrence.supportagent.retrieval.RetrievalMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 一次内存评测运行的状态、进度、指标和逐条结果。
 *
 * @param evaluationRunId 当前进程内定位本次运行的 UUID
 * @param mode 本次固定使用且不会被自动修改的检索模式
 * @param status 后台运行生命周期状态
 * @param completedCases 已完成用例数量
 * @param totalCases 本次选中的用例总数
 * @param metrics 全部成功后计算的五项指标，未完成或失败时为空
 * @param results 按固定数据集顺序保存的逐条结果
 * @param failureMessage 整次运行失败的脱敏摘要，正常时为空
 * @param startedAt 接收运行请求的 UTC 时间
 * @param finishedAt 成功或失败结束的 UTC 时间，运行中为空
 * @param context 数据集、提交、模型和检索参数的不可变复现上下文
 * @param retrievalLatency 全部已完成用例的检索耗时摘要
 */
public record RetrievalEvaluationRun(UUID evaluationRunId, RetrievalMode mode,
                                     Status status, int completedCases, int totalCases,
                                     RetrievalEvaluationMetrics metrics,
                                     List<RetrievalEvaluationCaseResult> results,
                                     String failureMessage, Instant startedAt,
                                     Instant finishedAt,
                                     RetrievalEvaluationContext context,
                                     LatencySummary retrievalLatency) {
    /** 评测运行生命周期。 */
    public enum Status {
        /** 已接收但尚未开始。 */ PENDING,
        /** 正在逐条检索。 */ RUNNING,
        /** 已生成完整指标和报告。 */ SUCCEEDED,
        /** 发生依赖或执行故障。 */ FAILED
    }
}
