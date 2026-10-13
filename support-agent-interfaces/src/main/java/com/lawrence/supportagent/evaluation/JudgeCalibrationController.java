package com.lawrence.supportagent.evaluation;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.sharedkernel.api.ApiResponseFactory;
import com.lawrence.supportagent.sharedkernel.api.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 按模型和模板版本分别报告人工一致性，不能替代发布门禁。 */
@RestController
@RequestMapping("/api/v1/admin/judge-reports")
public class JudgeCalibrationController {
    private final JudgeUseCase useCase;
    private final ApiResponseFactory responses;
    /** 注入评审报告用例和统一响应。 */
    public JudgeCalibrationController(JudgeUseCase useCase, ApiResponseFactory responses) {
        this.useCase=useCase; this.responses=responses;
    }
    /** 返回人工已决样本最新运行的聚合报告，不重复计算复测样本。 */
    @GetMapping
    @Operation(summary="查询各Judge版本人工一致性报告")
    public ApiResult<List<Report>> report(@AuthenticationPrincipal AuthenticatedUser actor, HttpServletRequest request) {
        return responses.success(useCase.report(actor).stream().map(Report::from).toList(),request);
    }
    /** 有效样本达到30且全部量化阈值满足才可报告辅助评审门槛通过。 */
    public record Report(
            @Schema(description="实际评审模型名") String model,
            @Schema(description="实际Prompt/Schema版本") String promptVersion,
            @Schema(description="去重后的人工已决样本数") int sampleCount,
            @Schema(description="三次评分均有效的样本数") int fullyValidSampleCount,
            @Schema(description="五维按事实引用完整操作清晰排列，平均绝对误差；无有效样本为空") List<Double> meanAbsoluteErrors,
            @Schema(description="五维精确一致率0～1，顺序同MAE") List<Double> exactAgreementRates,
            @Schema(description="完整有效样本的三次评分完全一致率0～1，无有效样本为空") Double repeatStabilityRate,
            @Schema(description="至少30样本且五维MAE≤0.3、一致率≥80%、复测一致率≥90%、无截断") boolean qualityThresholdPassed,
            @Schema(description="最近1000条输入窗口被截断，不能通过质量验收") boolean truncated,
            @Schema(description="来源回答模型元数据缺失，同源偏差尚未验证") boolean sourceModelBiasUnverified,
            @Schema(description="逐样本数值摘要，不返回评分正文；每份只计最新运行") List<Comparison> samples) {
        /** 转换聚合结果，复用已按字段说明的运行响应。 */
        public static Report from(JudgeUseCase.Calibration value) {
            return new Report(value.model(),value.promptVersion(),value.sampleCount(),value.fullyValidSampleCount(),
                    value.meanAbsoluteErrors(),value.exactAgreementRates(),value.repeatStabilityRate(),
                    value.qualityThresholdPassed(),value.truncated(),value.sourceModelBiasUnverified(),
                    value.samples().stream().map(Comparison::from).toList());
        }
    }
    /** 高估、低估、失败与成本摘要；可按sampleId查询评分详情。 */
    public record Comparison(
            @Schema(description="脱敏样本UUID") java.util.UUID sampleId,
            @Schema(description="最新运行UUID") java.util.UUID runId,
            @Schema(description="五维平均分差，正数高估、负数低估") List<Double> signedMeanErrors,
            @Schema(description="五维平均绝对误差") List<Double> meanAbsoluteErrors,
            @Schema(description="三次有效时是否完全一致，可空") Boolean repeatStable,
            @Schema(description="失败复测次数") long failureCount,
            @Schema(description="三次总耗时毫秒") long elapsedMillis,
            @Schema(description="三次实际输入Token；任一次未知则为空") Long inputTokens,
            @Schema(description="三次实际输出Token；任一次未知则为空") Long outputTokens) {
        /** 只保留数值，不把逐维理由扩散到聚合报告。 */
        public static Comparison from(JudgeUseCase.View value) {
            var attempts=value.attempts();
            boolean known=attempts.size()==3 && attempts.stream().allMatch(item->item.result()!=null
                    && item.result().inputTokens()!=null && item.result().outputTokens()!=null);
            return new Comparison(value.sampleId(),value.runId(),value.signedMeanErrors(),value.meanAbsoluteErrors(),
                    value.repeatStable(),attempts.stream().filter(item->item.failure()!=null).count(),
                    attempts.stream().mapToLong(JudgeRunRepository.Attempt::elapsedMillis).sum(),
                    known ? attempts.stream().mapToLong(item->item.result().inputTokens()).sum() : null,
                    known ? attempts.stream().mapToLong(item->item.result().outputTokens()).sum() : null);
        }
    }
}
