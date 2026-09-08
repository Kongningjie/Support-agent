package com.lawrence.supportagent.knowledge;

import com.lawrence.supportagent.sharedkernel.api.ApiResponseFactory;
import com.lawrence.supportagent.sharedkernel.api.ApiResult;
import com.lawrence.supportagent.sharedkernel.api.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 把阶段 3 托管知识文档 HTTP 协议适配到应用用例。 */
@Validated
@RestController
@RequestMapping("/api/v1/knowledge/documents")
public class ManagedDocumentController {
    private final ManagedDocumentCommandUseCase commandUseCase;
    private final ManagedDocumentQueryUseCase queryUseCase;
    private final ApiResponseFactory responses;

    /** 注入文档命令、查询用例和统一响应工厂。 */
    public ManagedDocumentController(ManagedDocumentCommandUseCase commandUseCase,
                                     ManagedDocumentQueryUseCase queryUseCase,
                                     ApiResponseFactory responses) {
        this.commandUseCase = commandUseCase;
        this.queryUseCase = queryUseCase;
        this.responses = responses;
    }

    /** 由直接文本创建托管知识草稿。 */
    @Operation(summary = "由直接文本创建知识草稿")
    @PostMapping("/text")
    public ResponseEntity<ApiResult<DocumentResponse>> createText(
            @Valid @RequestBody CreateTextRequest body, HttpServletRequest request) {
        DocumentResponse result = DocumentResponse.from(commandUseCase.createText(
                body.title(), body.content(), body.idempotencyKey()));
        return ResponseEntity.status(HttpStatus.CREATED).body(responses.success(result, request));
    }

    /** 上传单个 Markdown 或 TXT 文件并创建草稿。 */
    @Operation(summary = "上传 Markdown 或 TXT 知识草稿")
    @PostMapping(path = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResult<DocumentResponse>> createFile(
            @RequestPart(required = false) String title,
            @RequestPart @NotNull MultipartFile file,
            @RequestPart @NotBlank @Size(max = 160) String idempotencyKey,
            HttpServletRequest request) {
        try {
            ManagedDocumentDetails created = commandUseCase.createFile(title,
                    file.getOriginalFilename(), file.getContentType(), file.getBytes(), idempotencyKey);
            return ResponseEntity.status(HttpStatus.CREATED).body(
                    responses.success(DocumentResponse.from(created), request));
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取上传文件", exception);
        }
    }

    /** 查询未删除托管文档详情和完整正文。 */
    @Operation(summary = "查询知识文档详情")
    @GetMapping("/{documentId}")
    public ApiResult<DocumentResponse> get(@PathVariable long documentId,
                                           HttpServletRequest request) {
        return responses.success(DocumentResponse.from(queryUseCase.get(documentId)), request);
    }

    /** 按状态和关键词分页查询不含正文的文档摘要。 */
    @Operation(summary = "分页查询知识文档")
    @GetMapping
    public ApiResult<PageResult<DocumentSummaryResponse>> page(
            @RequestParam(required = false) ManagedDocumentStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        ManagedDocumentPage result = queryUseCase.page(status, keyword, page, size);
        List<DocumentSummaryResponse> items = result.items().stream()
                .map(DocumentSummaryResponse::from).toList();
        return responses.success(new PageResult<>(items, result.page(), result.size(),
                result.totalElements(), result.totalPages()), request);
    }

    /** 修改版本匹配的草稿或索引失败文档。 */
    @Operation(summary = "修改知识文档草稿")
    @PutMapping("/{documentId}/draft")
    public ApiResult<DocumentResponse> revise(@PathVariable long documentId,
                                              @Valid @RequestBody ReviseRequest body,
                                              HttpServletRequest request) {
        return responses.success(DocumentResponse.from(commandUseCase.revise(documentId,
                body.title(), body.content(), body.version())), request);
    }

    /** 发起异步索引并返回索引任务 ID。 */
    @Operation(summary = "发布知识文档")
    @PostMapping("/{documentId}/publish")
    public ApiResult<ActionResponse> publish(@PathVariable long documentId,
                                             @Valid @RequestBody VersionedActionRequest body,
                                             HttpServletRequest request) {
        return responses.success(ActionResponse.from(commandUseCase.publish(documentId,
                body.version(), body.idempotencyKey())), request);
    }

    /** 归档已发布文档并返回异步删除任务 ID。 */
    @Operation(summary = "归档知识文档")
    @PostMapping("/{documentId}/archive")
    public ApiResult<ActionResponse> archive(@PathVariable long documentId,
                                             @Valid @RequestBody ArchiveRequest body,
                                             HttpServletRequest request) {
        return responses.success(ActionResponse.from(commandUseCase.archive(documentId,
                body.version(), body.archiveReason(), body.idempotencyKey())), request);
    }

    /** 软删除版本匹配且从未发布的草稿。 */
    @Operation(summary = "删除从未发布的知识草稿")
    @DeleteMapping("/{documentId}")
    public ApiResult<Void> deleteDraft(@PathVariable long documentId,
                                       @RequestParam @PositiveOrZero long version,
                                       HttpServletRequest request) {
        commandUseCase.deleteDraft(documentId, version);
        return responses.success(null, request);
    }

    /**
     * 直接文本创建请求。
     *
     * @param title 文档标题，去除首尾空白后 1～160 字符
     * @param content UTF-8 文本正文，规范化后非空且最大 1 MiB
     * @param idempotencyKey 本次创建操作的幂等键
     */
    public record CreateTextRequest(
            @Schema(description = "文档标题", example = "MySQL 连接故障排查")
            @NotBlank @Size(max = 160) String title,
            @Schema(description = "知识正文", example = "检查 ERROR_CODE 后确认连接参数。")
            @NotBlank @Size(max = 1048576) String content,
            @Schema(description = "创建操作幂等键", example = "knowledge-create-001")
            @NotBlank @Size(max = 160) String idempotencyKey) {
    }

    /**
     * 草稿修改请求。
     *
     * @param title 修改后的完整标题
     * @param content 修改后的完整正文
     * @param version 客户端读取到的乐观锁版本
     */
    public record ReviseRequest(@NotBlank @Size(max = 160) String title,
                                @NotBlank @Size(max = 1048576) String content,
                                @NotNull @PositiveOrZero Long version) {
    }

    /**
     * 发布动作请求。
     *
     * @param version 当前文档版本
     * @param idempotencyKey 本次发布动作幂等键
     */
    public record VersionedActionRequest(@NotNull @PositiveOrZero Long version,
                                         @NotBlank @Size(max = 160) String idempotencyKey) {
    }

    /**
     * 归档动作请求。
     *
     * @param version 当前文档版本
     * @param archiveReason 人工归档原因，1～500 字符
     * @param idempotencyKey 本次归档动作幂等键
     */
    public record ArchiveRequest(@NotNull @PositiveOrZero Long version,
                                 @NotBlank @Size(max = 500) String archiveReason,
                                 @NotBlank @Size(max = 160) String idempotencyKey) {
    }

    /**
     * 文档详情响应。
     *
     * @param documentId 十进制字符串形式的文档 ID
     * @param title 文档标题
     * @param inputType 服务端确认的输入类型
     * @param originalFileName 上传原始安全文件名，直接文本时为空
     * @param mediaType 服务端确认的媒体类型
     * @param rawContent 规范化后的完整正文
     * @param contentHash 正文 SHA-256 哈希
     * @param status 当前文档状态
     * @param version 当前乐观锁版本
     * @param indexFailureReason 索引最终失败的安全摘要
     * @param archiveReason 人工归档原因
     * @param createdAt 创建 UTC 时间
     * @param updatedAt 最近更新 UTC 时间
     * @param publishedAt 成功发布时间
     * @param archivedAt 归档时间
     */
    public record DocumentResponse(String documentId, String title, DocumentInputType inputType,
                                   String originalFileName, String mediaType, String rawContent,
                                   String contentHash, ManagedDocumentStatus status, long version,
                                   String indexFailureReason, String archiveReason,
                                   Instant createdAt, Instant updatedAt,
                                   Instant publishedAt, Instant archivedAt) {
        /** 从应用层详情生成字符串 ID 的接口响应。 */
        public static DocumentResponse from(ManagedDocumentDetails value) {
            return new DocumentResponse(Long.toString(value.id()), value.title(), value.inputType(),
                    value.originalFileName(), value.mediaType(), value.rawContent(),
                    value.contentHash(), value.status(), value.version(), value.indexFailureReason(),
                    value.archiveReason(), value.createdAt(), value.updatedAt(),
                    value.publishedAt(), value.archivedAt());
        }
    }

    /**
     * 不含正文的分页摘要响应。
     *
     * @param documentId 十进制字符串形式的文档 ID
     * @param title 文档标题
     * @param inputType 输入类型
     * @param status 当前状态
     * @param version 当前版本
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     * @param publishedAt 成功发布时间
     */
    public record DocumentSummaryResponse(String documentId, String title,
                                          DocumentInputType inputType,
                                          ManagedDocumentStatus status, long version,
                                          Instant createdAt, Instant updatedAt,
                                          Instant publishedAt) {
        /** 从应用层摘要生成字符串 ID 的接口响应。 */
        public static DocumentSummaryResponse from(ManagedDocumentSummary value) {
            return new DocumentSummaryResponse(Long.toString(value.id()), value.title(),
                    value.inputType(), value.status(), value.version(), value.createdAt(),
                    value.updatedAt(), value.publishedAt());
        }
    }

    /**
     * 发布或归档动作响应。
     *
     * @param document 状态已变化的文档详情
     * @param taskId 负责索引或删除的异步任务十进制字符串 ID
     */
    public record ActionResponse(DocumentResponse document, String taskId) {
        /** 从应用动作结果生成字符串任务 ID 的接口响应。 */
        public static ActionResponse from(ManagedDocumentActionResult value) {
            return new ActionResponse(DocumentResponse.from(value.document()),
                    Long.toString(value.taskId()));
        }
    }
}
