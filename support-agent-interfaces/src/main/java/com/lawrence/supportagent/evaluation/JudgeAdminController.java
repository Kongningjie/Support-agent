package com.lawrence.supportagent.evaluation;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.sharedkernel.api.ApiResponseFactory;
import com.lawrence.supportagent.sharedkernel.api.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 管理员 Judge 执行和读取；用例强制人工盲評可见边界。 */
@RestController
@RequestMapping("/api/v1/admin/human-evaluations/{sampleId}/judge-runs")
public class JudgeAdminController {
    private final JudgeUseCase useCase;
    private final ApiResponseFactory responses;
    /** 注入独立评审用例和统一响应工厂。 */
    public JudgeAdminController(JudgeUseCase useCase, ApiResponseFactory responses) {
        this.useCase=useCase; this.responses=responses;
    }
    /** 三次复测长请求仅发生在管理员显式操作中。 */
    @Operation(summary="执行单样本 Judge 三次复测")
    @PostMapping
    public ApiResult<Response> execute(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID sampleId, @Valid @RequestBody Request body, HttpServletRequest request) {
        return responses.success(Response.from(useCase.execute(actor,sampleId,body.idempotencyKey())),request);
    }
    /** 未决样本隐藏 Judge 分数和理由，完成后才能对照。 */
    @Operation(summary="查询样本最近二十次 Judge 运行")
    @GetMapping
    public ApiResult<List<Response>> list(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID sampleId, HttpServletRequest request) {
        return responses.success(useCase.list(actor,sampleId).stream().map(Response::from).toList(),request);
    }
    /** 执行请求的 UUID 在结果不确定时必须复用。 */
    public record Request(@NotNull @Schema(description="必填幂等 UUID；相同操作重试必须复用") UUID idempotencyKey) { }
    /** 五维评分的证据位置；Unicode 半开区间，不适用时同时为空。 */
    public record Dimension(
            @Schema(description="整数0～2，0不满足、1部分满足、2满足") int score,
            @Schema(description="简短公开评分依据，最多500字符，无敏感信息") String reason,
            @Schema(description="原证据0起Unicode代码点位置，可空") Integer evidenceStart,
            @Schema(description="证据末尾排他位置，可空") Integer evidenceEnd) { }
    /** 单次结果，Token 未由供应商报告时为空。 */
    public record Attempt(
            @Schema(description="复测序号1～3") int ordinal,
            @Schema(description="五维按事实、引用、完整、操作、清晰排列，失败为空") List<Dimension> dimensions,
            @Schema(description="安全失败分类，成功为空") String failure,
            @Schema(description="调用耗时毫秒") long elapsedMillis,
            @Schema(description="实际输入Token，可空，不以估算值冒充") Long inputTokens,
            @Schema(description="实际输出Token，可空") Long outputTokens) {
        /** 裁剪为结构化安全响应。 */
        public static Attempt from(JudgeRunRepository.Attempt value) {
            var result=value.result();
            return new Attempt(value.ordinal(), result==null ? null : result.dimensions().stream()
                    .map(item->new Dimension(item.score(),item.reason(),item.evidenceStart(),item.evidenceEnd())).toList(),
                    value.failure(),value.elapsedMillis(),result==null ? null : result.inputTokens(),
                    result==null ? null : result.outputTokens());
        }
    }
    /** 运行元数据和人工对照；未决时所有评审正文保持隐藏。 */
    public record Response(
            @Schema(description="Judge运行UUID") UUID runId,
            @Schema(description="关联的脱敏人工样本UUID") UUID sampleId,
            @Schema(description="重试复用的幂等UUID") UUID idempotencyKey,
            @Schema(description="实际Judge模型名，来源回答模型未知") String model,
            @Schema(description="Prompt哈希与Schema版本") String promptVersion,
            @Schema(description="RUNNING执行中、COMPLETED有有效结果、FAILED全部失败或租约过期") String status,
            @Schema(description="单次输入Token保守估算，非供应商用量") int estimatedInputTokens,
            @Schema(description="三次复测；人工未决时为空数组") List<Attempt> attempts,
            @Schema(description="五维MAE按事实、引用、完整、操作、清晰排列，未决或无有效结果为空") List<Double> meanAbsoluteErrors,
            @Schema(description="逐维精确一致率0～1，顺序同MAE") List<Double> exactAgreementRates,
            @Schema(description="五维平均Judge分减人工分，正数高估、负数低估，未决为空") List<Double> signedMeanErrors,
            @Schema(description="三次评分是否完全相同；不足三次有效时为空") Boolean repeatStable,
            @Schema(description="人工未决所以隐藏结果") boolean hiddenUntilHumanDecision) {
        /** 公开裁剪后的用例结果。 */
        public static Response from(JudgeUseCase.View value) {
            return new Response(value.runId(),value.sampleId(),value.idempotencyKey(),value.model(),value.promptVersion(),value.status(),
                    value.estimatedInputTokens(),value.attempts().stream().map(Attempt::from).toList(),
                    value.meanAbsoluteErrors(),value.exactAgreementRates(),value.signedMeanErrors(),value.repeatStable(),value.hiddenUntilHumanDecision());
        }
    }
}
