package com.lawrence.supportagent.evaluation;

import com.lawrence.supportagent.retrieval.RetrievalMode;
import com.lawrence.supportagent.sharedkernel.api.ApiResponseFactory;
import com.lawrence.supportagent.sharedkernel.api.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 仅在开发和测试环境暴露固定检索评测运行接口。 */
@RestController
@Profile({"dev", "test"})
@RequestMapping("/api/v1/retrieval-evaluations")
public class RetrievalEvaluationController {
    private final RetrievalEvaluationService evaluations;
    private final ApiResponseFactory responses;

    /** 注入评测服务和统一响应工厂。 */
    public RetrievalEvaluationController(RetrievalEvaluationService evaluations,
                                         ApiResponseFactory responses) {
        this.evaluations = evaluations;
        this.responses = responses;
    }

    /** 启动一种检索模式的全部或指定用例评测。 */
    @Operation(summary = "启动固定检索评测", description = "仅 dev/test 可用，不修改数据集或检索参数")
    @PostMapping
    public ResponseEntity<ApiResult<RunResponse>> start(@Valid @RequestBody StartRequest body,
                                                         HttpServletRequest request) {
        EvaluationDatasetKind kind = body.datasetKind() == null
                ? EvaluationDatasetKind.LOCKED_REGRESSION : body.datasetKind();
        RetrievalEvaluationRun run = evaluations.start(kind, body.mode(), body.caseIds());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(responses.success(RunResponse.from(run), request));
    }

    /** 查询内存中的评测进度、指标和逐条结果。 */
    @Operation(summary = "查询固定检索评测结果")
    @GetMapping("/{evaluationRunId}")
    public ApiResult<RunResponse> get(@PathVariable UUID evaluationRunId,
                                      HttpServletRequest request) {
        return responses.success(RunResponse.from(evaluations.get(evaluationRunId)), request);
    }

    /**
     * @param datasetKind 数据集用途；为空兼容原调用并使用锁定回归集
     * @param mode 四种固定模式之一
     * @param caseIds 可选用例 ID 子集，空表示当前数据集全部用例
     */
    public record StartRequest(
            @Schema(description = "LOCKED_REGRESSION 锁定回归集或 OPTIMIZATION_DEVELOPMENT 优化开发集；为空使用锁定集",
                    nullable = true, example = "LOCKED_REGRESSION")
            EvaluationDatasetKind datasetKind,
            @NotNull @Schema(description = "BM25_ONLY、VECTOR_ONLY、HYBRID 或 HYBRID_RERANK")
            RetrievalMode mode,
            @Size(max = 150) @Schema(description = "可选固定用例 ID；为空运行当前数据集全部用例")
            List<String> caseIds) { }

    /**
     * 评测运行响应。
     *
     * @param evaluationRunId 运行 UUID @param mode 检索模式 @param status 运行状态
     * @param completedCases 已完成数 @param totalCases 总数 @param metrics 完成后的五项指标
     * @param diagnostics 完成后的可靠性门槛诊断
     * @param results 逐条结果 @param failureMessage 失败摘要 @param startedAt 开始时间
     * @param finishedAt 结束时间 @param context 数据、提交、模型与参数复现上下文
     * @param retrievalLatency 检索耗时样本数量、平均值、P95 和最大值
     */
    public record RunResponse(
            @Schema(description = "本次评测运行 UUID") UUID evaluationRunId,
            @Schema(description = "本次使用的固定检索模式") RetrievalMode mode,
            @Schema(description = "PENDING、RUNNING、SUCCEEDED 或 FAILED")
            RetrievalEvaluationRun.Status status,
            @Schema(description = "已经执行完成的用例数量") int completedCases,
            @Schema(description = "本次需要执行的用例总数") int totalCases,
            @Schema(description = "成功完成后生成的五项 0～1 指标", nullable = true)
            MetricsResponse metrics,
            @Schema(description = "成功完成后生成的可靠性门槛混淆计数和分类分数范围",
                    nullable = true)
            DiagnosticsResponse diagnostics,
            @Schema(description = "保持固定数据集顺序的逐条评测结果")
            List<CaseResultResponse> results,
            @Schema(description = "评测失败的脱敏摘要", nullable = true) String failureMessage,
            @Schema(description = "评测开始 UTC 时间") Instant startedAt,
            @Schema(description = "评测结束 UTC 时间", nullable = true) Instant finishedAt,
            @Schema(description = "数据集版本、哈希、Git 提交、模型名和检索参数快照")
            RetrievalEvaluationContext context,
            @Schema(description = "当前已完成用例的检索耗时统计")
            LatencySummary retrievalLatency) {
        /** 从应用运行快照创建接口响应。 */
        public static RunResponse from(RetrievalEvaluationRun value) {
            return new RunResponse(value.evaluationRunId(), value.mode(), value.status(),
                    value.completedCases(), value.totalCases(), MetricsResponse.from(value.metrics()),
                    DiagnosticsResponse.from(value.diagnostics()),
                    value.results().stream().map(CaseResultResponse::from).toList(),
                    value.failureMessage(), value.startedAt(), value.finishedAt(), value.context(),
                    value.retrievalLatency());
        }
    }

    /**
     * 可靠性门槛诊断响应。
     *
     * @param expectedGroundedCount 人工预期有知识的用例数
     * @param correctGroundedCount 有知识且被正确接受的用例数
     * @param falseNoHitCount 有知识但被错误拒绝的用例数
     * @param expectedNoHitCount 人工预期无知识的用例数
     * @param correctNoHitCount 无知识且被正确拒绝的用例数
     * @param falseGroundedCount 无知识但被错误接受的用例数
     * @param technicalFailureCount 实际发生检索技术故障的用例数
     * @param highestRerankScoreByCategory 各问题分类的候选最高分范围
     */
    public record DiagnosticsResponse(
            @Schema(description = "人工预期为有可靠知识的用例数量") int expectedGroundedCount,
            @Schema(description = "有知识且被可靠性规则正确接受的用例数量") int correctGroundedCount,
            @Schema(description = "有知识但被错误拒绝为无知识的用例数量") int falseNoHitCount,
            @Schema(description = "人工预期为无可靠知识的用例数量") int expectedNoHitCount,
            @Schema(description = "无知识且被可靠性规则正确拒绝的用例数量") int correctNoHitCount,
            @Schema(description = "无知识但被错误接受为有知识的用例数量") int falseGroundedCount,
            @Schema(description = "实际判定为检索技术故障的用例数量") int technicalFailureCount,
            @Schema(description = "按 DIRECT、NOISY 等冻结分类汇总的候选最高分范围")
            java.util.Map<String, ScoreRangeResponse> highestRerankScoreByCategory) {
        /** 从应用层诊断创建接口响应；运行未完成时保持为空。 */
        public static DiagnosticsResponse from(RetrievalEvaluationDiagnostics value) {
            if (value == null) return null;
            java.util.Map<String, ScoreRangeResponse> ranges = new java.util.LinkedHashMap<>();
            value.highestRerankScoreByCategory().forEach((category, range) ->
                    ranges.put(category, ScoreRangeResponse.from(range)));
            return new DiagnosticsResponse(value.expectedGroundedCount(),
                    value.correctGroundedCount(), value.falseNoHitCount(),
                    value.expectedNoHitCount(), value.correctNoHitCount(),
                    value.falseGroundedCount(), value.technicalFailureCount(),
                    java.util.Map.copyOf(ranges));
        }
    }

    /**
     * 单个问题分类的候选最高分范围。
     *
     * @param sampleCount 具有有效 Rerank 分数的样本数量
     * @param minimum 分类内最低的候选最高分
     * @param maximum 分类内最高的候选最高分
     */
    public record ScoreRangeResponse(
            @Schema(description = "具有有效 Rerank 分数的样本数量") int sampleCount,
            @Schema(description = "分类内最低的候选最高分") double minimum,
            @Schema(description = "分类内最高的候选最高分") double maximum) {
        /** 从应用层分数范围创建接口响应。 */
        public static ScoreRangeResponse from(RetrievalEvaluationScoreRange value) {
            return new ScoreRangeResponse(value.sampleCount(), value.minimum(), value.maximum());
        }
    }

    /**
     * 五项检索指标响应。
     *
     * @param recallAt5 前五来源召回率 @param mrrAt10 前十首个相关来源倒数排名
     * @param ndcgAt5 前五归一化折损累计增益 @param noHitAccuracy 无知识判断准确率
     * @param exactTermRecall 精确技术词用例召回率
     */
    public record MetricsResponse(
            @Schema(description = "Recall@5，前五结果覆盖相关来源的比例") double recallAt5,
            @Schema(description = "MRR@10，前十首个相关来源倒数排名均值") double mrrAt10,
            @Schema(description = "nDCG@5，前五相关来源排序质量") double ndcgAt5,
            @Schema(description = "无知识用例被正确判为无可靠知识的比例") double noHitAccuracy,
            @Schema(description = "精确术语用例在前五结果中完整保留术语的比例") double exactTermRecall) {
        /** 从应用指标创建接口响应；运行未完成时保持为空。 */
        public static MetricsResponse from(RetrievalEvaluationMetrics value) {
            return value == null ? null : new MetricsResponse(value.recallAt5(), value.mrrAt10(),
                    value.ndcgAt5(), value.noHitAccuracy(), value.exactTermRecall());
        }
    }

    /**
     * 单条评测结果响应。
     *
     * @param caseId 固定用例 ID @param expectedStatus 人工预期状态 @param actualStatus 实际状态
     * @param rankedSourceKeys 去重后的有序稳定来源键 @param exactTermsSatisfied 是否覆盖全部要求术语
     * @param highestRerankScore 原始候选中的最高 Rerank 分数
     * @param reliableEvidenceCount 通过可靠性规则的最终证据数量
     * @param decisionReason 接受、拒绝或失败的稳定原因
     * @param failureMessage 单条失败摘要 @param retrievalDurationMs 单条检索耗时毫秒
     */
    public record CaseResultResponse(
            @Schema(description = "固定评测用例 ID", example = "KNOWN-001") String caseId,
            @Schema(description = "人工标注的预期三态结果") com.lawrence.supportagent.retrieval.RetrievalStatus expectedStatus,
            @Schema(description = "检索实际产生的三态结果") com.lawrence.supportagent.retrieval.RetrievalStatus actualStatus,
            @Schema(description = "前十候选按首次出现去重后的稳定 sourceKey") List<String> rankedSourceKeys,
            @Schema(description = "前五候选是否覆盖要求的全部精确技术词") boolean exactTermsSatisfied,
            @Schema(description = "原始候选中的最高 Rerank 分数；未执行或降级时为空",
                    nullable = true) Double highestRerankScore,
            @Schema(description = "应用生产可靠性规则后保留的证据数量")
            int reliableEvidenceCount,
            @Schema(description = "RETRIEVAL_BRANCH_FAILED、NO_CANDIDATE、BELOW_GROUNDED_THRESHOLD、"
                    + "INSUFFICIENT_DEGRADED_SIGNAL 或 RELIABLE_EVIDENCE_PRESENT")
            com.lawrence.supportagent.retrieval.RetrievalDecisionReason decisionReason,
            @Schema(description = "单条执行失败的脱敏摘要", nullable = true) String failureMessage,
            @Schema(description = "单条检索端到端耗时，单位毫秒", example = "125")
            long retrievalDurationMs) {
        /** 从应用单条结果创建接口响应。 */
        public static CaseResultResponse from(RetrievalEvaluationCaseResult value) {
            return new CaseResultResponse(value.caseId(), value.expectedStatus(), value.actualStatus(),
                    value.rankedSourceKeys(), value.exactTermsSatisfied(),
                    value.highestRerankScore(), value.reliableEvidenceCount(),
                    value.decisionReason(), value.failureMessage(), value.retrievalDurationMs());
        }
    }
}
