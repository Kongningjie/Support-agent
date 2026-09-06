package com.lawrence.supportagent.ticket;

import com.lawrence.supportagent.sharedkernel.api.ApiResponseFactory;
import com.lawrence.supportagent.sharedkernel.api.ApiResult;
import com.lawrence.supportagent.sharedkernel.api.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 将阶段 2 工单 HTTP 协议适配到应用用例，不直接访问持久化组件。 */
@Validated
@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {
    private final TicketCommandUseCase commandUseCase;
    private final TicketQueryUseCase queryUseCase;
    private final ApiResponseFactory responses;

    /** 注入工单命令、查询用例和统一响应工厂。 */
    public TicketController(TicketCommandUseCase commandUseCase,
                            TicketQueryUseCase queryUseCase, ApiResponseFactory responses) {
        this.commandUseCase = commandUseCase;
        this.queryUseCase = queryUseCase;
        this.responses = responses;
    }

    /** 手工创建尚未提交的工单草稿。 */
    @Operation(summary = "手工创建工单草稿")
    @PostMapping("/drafts")
    public ResponseEntity<ApiResult<TicketResponse>> createDraft(
            @Valid @RequestBody CreateDraftRequest body, HttpServletRequest request) {
        TicketResponse result = TicketResponse.from(commandUseCase.createDraft(body.title(),
                body.problemDescription(), body.attemptedActions(), body.idempotencyKey()));
        return ResponseEntity.status(HttpStatus.CREATED).body(responses.success(result, request));
    }

    /** 按稳定对外编号查询工单详情。 */
    @Operation(summary = "查询工单详情")
    @GetMapping("/{ticketNo}")
    public ApiResult<TicketResponse> get(
                                         @Parameter(description = "T 加 12 位数字的工单编号",
                                                 example = "T000000000001")
                                         @PathVariable String ticketNo,
                                         HttpServletRequest request) {
        return responses.success(TicketResponse.from(queryUseCase.get(ticketNo)), request);
    }

    /** 修改版本匹配的工单草稿。 */
    @Operation(summary = "修改工单草稿")
    @PutMapping("/{ticketNo}/draft")
    public ApiResult<TicketResponse> reviseDraft(
                                                  @Parameter(description = "待修改的工单编号",
                                                          example = "T000000000001")
                                                  @PathVariable String ticketNo,
                                                  @Valid @RequestBody ReviseDraftRequest body,
                                                  HttpServletRequest request) {
        TicketDetails result = commandUseCase.reviseDraft(ticketNo, body.title(),
                body.problemDescription(), body.attemptedActions(), body.version());
        return responses.success(TicketResponse.from(result), request);
    }

    /** 将版本匹配的草稿幂等提交为开放工单。 */
    @Operation(summary = "提交工单草稿")
    @PostMapping("/{ticketNo}/submit")
    public ApiResult<TicketResponse> submit(
                                             @Parameter(description = "待提交的工单编号",
                                                     example = "T000000000001")
                                             @PathVariable String ticketNo,
                                             @Valid @RequestBody VersionedActionRequest body,
                                             HttpServletRequest request) {
        TicketDetails result = commandUseCase.submit(ticketNo, body.version(), body.idempotencyKey());
        return responses.success(TicketResponse.from(result), request);
    }

    /** 关闭版本匹配的草稿或开放工单。 */
    @Operation(summary = "关闭工单")
    @PostMapping("/{ticketNo}/close")
    public ApiResult<TicketResponse> close(
                                            @Parameter(description = "待关闭的工单编号",
                                                    example = "T000000000001")
                                            @PathVariable String ticketNo,
                                            @Valid @RequestBody CloseTicketRequest body,
                                            HttpServletRequest request) {
        TicketDetails result = commandUseCase.close(ticketNo, body.closeReason(),
                body.version(), body.idempotencyKey());
        return responses.success(TicketResponse.from(result), request);
    }

    /** 按状态和关键词分页查询工单摘要。 */
    @Operation(summary = "分页查询工单")
    @GetMapping
    public ApiResult<PageResult<TicketSummaryResponse>> page(
            @Parameter(description = "可空工单状态过滤条件", example = "OPEN")
            @RequestParam(required = false) TicketStatus status,
            @Parameter(description = "可空标题或问题描述关键词，最大 160 字符")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "从 1 开始的页码", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量，范围 1～100", example = "20")
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        TicketPage result = queryUseCase.page(status, keyword, page, size);
        List<TicketSummaryResponse> items = result.items().stream()
                .map(TicketSummaryResponse::from).toList();
        return responses.success(new PageResult<>(items, result.page(), result.size(),
                result.totalElements(), result.totalPages()), request);
    }

    /**
     * 手工创建工单草稿请求。
     *
     * @param title 工单标题，去除首尾空白后 1～160 字符
     * @param problemDescription 问题现象和背景，1～8000 字符
     * @param attemptedActions 用户已尝试的操作和结果，可为空，最大 8000 字符
     * @param idempotencyKey 客户端生成的操作幂等键，1～160 字符
     */
    public record CreateDraftRequest(
            @Schema(description = "必填工单标题，去除首尾空白后 1～160 字符",
                    example = "应用启动时报数据库连接失败")
            @NotBlank @Size(max = 160) String title,
            @Schema(description = "必填问题现象、背景和错误信息，1～8000 字符",
                    example = "启动后提示 Connection refused")
            @NotBlank @Size(max = 8000) String problemDescription,
            @Schema(description = "已经尝试的操作及结果，最大 8000 字符；可以为空",
                    example = "已确认数据库进程正在运行", nullable = true)
            @Size(max = 8000) String attemptedActions,
            @Schema(description = "必填的本次创建操作幂等键，1～160 字符",
                    example = "ticket-create-20260904-001")
            @NotBlank @Size(max = 160) String idempotencyKey) {
    }

    /**
     * 修改工单草稿请求。
     *
     * @param title 新工单标题，1～160 字符
     * @param problemDescription 新问题描述，1～8000 字符
     * @param attemptedActions 新的已尝试操作，可为空，最大 8000 字符
     * @param version 客户端读取到的当前工单版本，从 0 开始
     */
    public record ReviseDraftRequest(
            @Schema(description = "必填的修改后工单标题，1～160 字符",
                    example = "应用启动时报 MySQL 连接失败")
            @NotBlank @Size(max = 160) String title,
            @Schema(description = "必填的修改后问题描述，1～8000 字符",
                    example = "启动后无法连接 localhost:3306")
            @NotBlank @Size(max = 8000) String problemDescription,
            @Schema(description = "修改后的已尝试操作，最大 8000 字符；可以为空",
                    example = "已检查端口占用", nullable = true)
            @Size(max = 8000) String attemptedActions,
            @Schema(description = "当前乐观锁版本", example = "0")
            @NotNull @PositiveOrZero Long version) {
    }

    /**
     * 只包含版本和幂等键的工单动作请求。
     *
     * @param version 客户端读取到的当前工单版本
     * @param idempotencyKey 本次动作的幂等键
     */
    public record VersionedActionRequest(
            @Schema(description = "当前乐观锁版本", example = "1")
            @NotNull @PositiveOrZero Long version,
            @Schema(description = "必填的本次提交操作幂等键，1～160 字符",
                    example = "ticket-submit-20260904-001")
            @NotBlank @Size(max = 160) String idempotencyKey) {
    }

    /**
     * 关闭工单请求。
     *
     * @param closeReason 未解决而关闭的人工原因，1～500 字符
     * @param version 客户端读取到的当前工单版本
     * @param idempotencyKey 本次关闭操作的幂等键
     */
    public record CloseTicketRequest(
            @Schema(description = "必填的未解决关闭原因，1～500 字符",
                    example = "问题无法复现，用户确认关闭")
            @NotBlank @Size(max = 500) String closeReason,
            @Schema(description = "当前乐观锁版本", example = "1")
            @NotNull @PositiveOrZero Long version,
            @Schema(description = "必填的本次关闭操作幂等键，1～160 字符",
                    example = "ticket-close-20260904-001")
            @NotBlank @Size(max = 160) String idempotencyKey) {
    }

    /**
     * 工单详情响应；不包含数据库内部主键。
     *
     * @param ticketNo 对外工单编号
     * @param title 工单标题
     * @param problemDescription 问题描述
     * @param attemptedActions 已尝试操作，可为空
     * @param status 当前状态
     * @param rootCause 解决后人工确认的根因，可为空
     * @param solution 解决后人工确认的方案，可为空
     * @param closeReason 关闭原因，可为空
     * @param version 当前版本
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     * @param resolvedAt 解决时间，可为空
     * @param closedAt 关闭时间，可为空
     */
    public record TicketResponse(
            @Schema(description = "T 加 12 位数字的工单编号", example = "T000000000001") String ticketNo,
            @Schema(description = "工单标题", example = "应用启动时报 MySQL 连接失败") String title,
            @Schema(description = "问题现象和背景", example = "启动后无法连接 localhost:3306") String problemDescription,
            @Schema(description = "用户已尝试操作；可以为空", example = "已检查端口映射",
                    nullable = true) String attemptedActions,
            @Schema(description = "工单状态", example = "OPEN") TicketStatus status,
            @Schema(description = "人工确认根因；仅 RESOLVED 有值", example = "数据库端口配置错误",
                    nullable = true) String rootCause,
            @Schema(description = "人工确认解决方案；仅 RESOLVED 有值", example = "修正端口后重启应用",
                    nullable = true) String solution,
            @Schema(description = "未解决关闭原因；仅 CLOSED 有值", example = "用户确认不再处理",
                    nullable = true) String closeReason,
            @Schema(description = "乐观锁版本", example = "1") long version,
            @Schema(description = "创建 UTC 时间", example = "2026-09-04T01:00:00Z") Instant createdAt,
            @Schema(description = "最近更新 UTC 时间", example = "2026-09-04T01:05:00Z") Instant updatedAt,
            @Schema(description = "解决 UTC 时间；可以为空", example = "2026-09-04T02:00:00Z",
                    nullable = true) Instant resolvedAt,
            @Schema(description = "关闭 UTC 时间；可以为空", example = "2026-09-04T02:00:00Z",
                    nullable = true) Instant closedAt) {
        /** 从应用层脱敏视图创建接口响应。 */
        public static TicketResponse from(TicketDetails value) {
            return new TicketResponse(value.ticketNo(), value.title(), value.problemDescription(),
                    value.attemptedActions(), value.status(), value.rootCause(), value.solution(),
                    value.closeReason(), value.version(), value.createdAt(), value.updatedAt(),
                    value.resolvedAt(), value.closedAt());
        }
    }

    /**
     * 工单分页摘要响应。
     *
     * @param ticketNo 对外工单编号
     * @param title 工单标题
     * @param status 当前状态
     * @param version 当前版本
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public record TicketSummaryResponse(
            @Schema(description = "对外工单编号", example = "T000000000001") String ticketNo,
            @Schema(description = "工单标题", example = "应用启动时报 MySQL 连接失败") String title,
            @Schema(description = "当前工单状态", example = "OPEN") TicketStatus status,
            @Schema(description = "当前乐观锁版本", example = "1") long version,
            @Schema(description = "创建 UTC 时间", example = "2026-09-04T01:00:00Z") Instant createdAt,
            @Schema(description = "最近更新 UTC 时间", example = "2026-09-04T01:05:00Z") Instant updatedAt) {
        /** 从应用层分页摘要创建接口响应。 */
        public static TicketSummaryResponse from(TicketSummary value) {
            return new TicketSummaryResponse(value.ticketNo(), value.title(), value.status(),
                    value.version(), value.createdAt(), value.updatedAt());
        }
    }
}
