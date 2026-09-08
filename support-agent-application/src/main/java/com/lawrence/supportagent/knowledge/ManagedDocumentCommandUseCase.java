package com.lawrence.supportagent.knowledge;

import com.lawrence.supportagent.asynctask.AggregateType;
import com.lawrence.supportagent.asynctask.AsyncTask;
import com.lawrence.supportagent.asynctask.AsyncTaskCreator;
import com.lawrence.supportagent.asynctask.AsyncTaskType;
import com.lawrence.supportagent.asynctask.port.AsyncTaskRepository;
import com.lawrence.supportagent.idempotency.IdempotencyCommand;
import com.lawrence.supportagent.idempotency.IdempotentExecutor;
import com.lawrence.supportagent.idempotency.IdempotentResource;
import com.lawrence.supportagent.idempotency.RequestFingerprint;
import com.lawrence.supportagent.knowledge.port.ManagedDocumentRepository;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.sharedkernel.port.OperatorProvider;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/** 编排托管文档导入、草稿维护、异步发布、归档和软删除。 */
public class ManagedDocumentCommandUseCase {
    private static final Duration IDEMPOTENCY_LEASE = Duration.ofSeconds(30);
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofDays(7);
    private static final Set<String> MARKDOWN_MEDIA_TYPES = Set.of(
            "text/markdown", "text/x-markdown", "text/plain", "application/octet-stream");
    private static final Set<String> TEXT_MEDIA_TYPES = Set.of(
            "text/plain", "application/octet-stream");
    private final ManagedDocumentRepository repository;
    private final AsyncTaskRepository taskRepository;
    private final ManagedDocumentQueryUseCase queryUseCase;
    private final AsyncTaskCreator taskCreator;
    private final IdempotentExecutor idempotentExecutor;
    private final OperatorProvider operatorProvider;
    private final TimeProvider timeProvider;
    private final DocumentContentPolicy contentPolicy;

    /** 注入文档、任务、幂等、审计和内容安全依赖。 */
    public ManagedDocumentCommandUseCase(ManagedDocumentRepository repository,
                                         AsyncTaskRepository taskRepository,
                                         ManagedDocumentQueryUseCase queryUseCase,
                                         AsyncTaskCreator taskCreator,
                                         IdempotentExecutor idempotentExecutor,
                                         OperatorProvider operatorProvider,
                                         TimeProvider timeProvider,
                                         DocumentContentPolicy contentPolicy) {
        this.repository = repository;
        this.taskRepository = taskRepository;
        this.queryUseCase = queryUseCase;
        this.taskCreator = taskCreator;
        this.idempotentExecutor = idempotentExecutor;
        this.operatorProvider = operatorProvider;
        this.timeProvider = timeProvider;
        this.contentPolicy = contentPolicy;
    }

    /** 幂等创建直接文本托管文档草稿。 */
    public ManagedDocumentDetails createText(String title, String content, String idempotencyKey) {
        String normalizedTitle = required(title, "文档标题", 160);
        DocumentContent normalizedContent = contentPolicy.normalizeDirectText(content);
        IdempotencyCommand command = command("KNOWLEDGE_CREATE_TEXT", idempotencyKey,
                RequestFingerprint.sha256(normalizedTitle, normalizedContent.sha256()));
        return idempotentExecutor.execute(command,
                () -> createDocument(normalizedTitle, DocumentInputType.DIRECT_TEXT,
                        null, "text/plain;charset=UTF-8", normalizedContent),
                id -> ManagedDocumentDetails.from(queryUseCase.requireDocument(id)));
    }

    /** 幂等创建单个 Markdown 或 TXT 上传文档草稿。 */
    public ManagedDocumentDetails createFile(String requestedTitle, String originalFileName,
                                              String declaredMediaType, byte[] bytes,
                                              String idempotencyKey) {
        FileMetadata metadata = fileMetadata(requestedTitle, originalFileName, declaredMediaType);
        DocumentContent normalizedContent = contentPolicy.decodeUploadedFile(bytes);
        IdempotencyCommand command = command("KNOWLEDGE_CREATE_FILE", idempotencyKey,
                RequestFingerprint.sha256(metadata.title, metadata.fileName,
                        metadata.mediaType, normalizedContent.sha256()));
        return idempotentExecutor.execute(command,
                () -> createDocument(metadata.title, metadata.inputType, metadata.fileName,
                        metadata.mediaType, normalizedContent),
                id -> ManagedDocumentDetails.from(queryUseCase.requireDocument(id)));
    }

    /** 修改版本匹配的草稿或失败文档，并重新执行安全及重复检测。 */
    public ManagedDocumentDetails revise(long documentId, String title, String content, long version) {
        ManagedDocument current = requireVersion(queryUseCase.requireDocument(documentId), version);
        if (current.status() != ManagedDocumentStatus.DRAFT
                && current.status() != ManagedDocumentStatus.FAILED) {
            throw new ApplicationException(ErrorCode.KNOWLEDGE_STATUS_CONFLICT,
                    "只有草稿或索引失败文档可以修改");
        }
        String normalizedTitle = required(title, "文档标题", 160);
        DocumentContent normalizedContent = contentPolicy.normalizeDirectText(content);
        requireUniqueContent(normalizedContent.sha256(), documentId);
        ManagedDocument revised = current.revise(normalizedTitle, normalizedContent.normalizedText(),
                normalizedContent.sha256(), operator(), timeProvider.now());
        return ManagedDocumentDetails.from(repository.save(revised));
    }

    /** 幂等发起文档索引并返回可查询的任务 ID。 */
    public ManagedDocumentActionResult publish(long documentId, long version,
                                               String idempotencyKey) {
        IdempotencyCommand command = command("KNOWLEDGE_PUBLISH", idempotencyKey,
                RequestFingerprint.sha256(Long.toString(documentId), Long.toString(version)));
        return idempotentExecutor.execute(command, () -> {
            ManagedDocument current = requireVersion(queryUseCase.requireDocument(documentId), version);
            contentPolicy.verifyNoSensitiveContent(current.rawContent());
            requireUniqueContent(current.contentHash(), current.id());
            ManagedDocument indexing = repository.save(current.startIndexing(operator(), timeProvider.now()));
            AsyncTask task = taskCreator.create(AsyncTaskType.KNOWLEDGE_INDEX,
                    AggregateType.MANAGED_DOCUMENT, indexing.id(), indexing.version(),
                    "knowledge-index:" + indexing.id() + ":" + indexing.version(), operator());
            ManagedDocumentActionResult result = new ManagedDocumentActionResult(
                    ManagedDocumentDetails.from(indexing), task.id());
            return new IdempotentResource<>("ASYNC_TASK", task.id(), result);
        }, this::loadActionByTaskId);
    }

    /** 幂等归档已发布文档并投递 Elasticsearch 删除任务。 */
    public ManagedDocumentActionResult archive(long documentId, long version,
                                               String archiveReason, String idempotencyKey) {
        String normalizedReason = required(archiveReason, "归档原因", 500);
        IdempotencyCommand command = command("KNOWLEDGE_ARCHIVE", idempotencyKey,
                RequestFingerprint.sha256(Long.toString(documentId), Long.toString(version),
                        normalizedReason));
        return idempotentExecutor.execute(command, () -> {
            ManagedDocument current = requireVersion(queryUseCase.requireDocument(documentId), version);
            if (current.status() != ManagedDocumentStatus.PUBLISHED) {
                throw new ApplicationException(ErrorCode.KNOWLEDGE_STATUS_CONFLICT,
                        "只有已发布文档可以归档");
            }
            ManagedDocument archived = repository.save(current.archive(
                    normalizedReason, operator(), timeProvider.now()));
            AsyncTask task = taskCreator.create(AsyncTaskType.KNOWLEDGE_DELETE,
                    AggregateType.MANAGED_DOCUMENT, archived.id(), archived.version(),
                    "knowledge-delete:" + archived.id() + ":" + archived.version(), operator());
            ManagedDocumentActionResult result = new ManagedDocumentActionResult(
                    ManagedDocumentDetails.from(archived), task.id());
            return new IdempotentResource<>("ASYNC_TASK", task.id(), result);
        }, this::loadActionByTaskId);
    }

    /** 软删除版本匹配且从未发布的草稿。 */
    public void deleteDraft(long documentId, long version) {
        ManagedDocument current = requireVersion(queryUseCase.requireDocument(documentId), version);
        if (current.status() != ManagedDocumentStatus.DRAFT || current.publishedAt() != null) {
            throw new ApplicationException(ErrorCode.KNOWLEDGE_STATUS_CONFLICT,
                    "只有从未发布的草稿可以删除");
        }
        repository.save(current.deleteDraft(operator(), timeProvider.now()));
    }

    /** 创建文档并将数据库并发唯一冲突转换为稳定业务错误。 */
    private IdempotentResource<ManagedDocumentDetails> createDocument(
            String title, DocumentInputType inputType, String fileName,
            String mediaType, DocumentContent content) {
        requireUniqueContent(content.sha256(), null);
        Instant now = timeProvider.now();
        ManagedDocument saved = repository.save(ManagedDocument.draft(title, inputType,
                fileName, mediaType, content.normalizedText(), content.sha256(), operator(), now));
        return new IdempotentResource<>("MANAGED_DOCUMENT", saved.id(),
                ManagedDocumentDetails.from(saved));
    }

    /** 由首次任务定位文档和任务，恢复发布或归档的幂等响应。 */
    private ManagedDocumentActionResult loadActionByTaskId(long taskId) {
        AsyncTask task = taskRepository.findById(taskId).orElseThrow(() ->
                new ApplicationException(ErrorCode.ASYNC_TASK_NOT_FOUND, "异步任务不存在"));
        return new ManagedDocumentActionResult(
                ManagedDocumentDetails.from(queryUseCase.requireDocument(task.aggregateId())), task.id());
    }

    /** 解析并校验上传文件名、扩展名、标题和声明媒体类型。 */
    private FileMetadata fileMetadata(String requestedTitle, String originalFileName,
                                      String declaredMediaType) {
        String fileName = safeFileName(originalFileName);
        String lower = fileName.toLowerCase(Locale.ROOT);
        DocumentInputType inputType;
        String mediaType;
        Set<String> allowedMediaTypes;
        int extensionLength;
        if (lower.endsWith(".markdown")) {
            inputType = DocumentInputType.MARKDOWN_FILE;
            mediaType = "text/markdown;charset=UTF-8";
            allowedMediaTypes = MARKDOWN_MEDIA_TYPES;
            extensionLength = 9;
        } else if (lower.endsWith(".md")) {
            inputType = DocumentInputType.MARKDOWN_FILE;
            mediaType = "text/markdown;charset=UTF-8";
            allowedMediaTypes = MARKDOWN_MEDIA_TYPES;
            extensionLength = 3;
        } else if (lower.endsWith(".txt")) {
            inputType = DocumentInputType.TEXT_FILE;
            mediaType = "text/plain;charset=UTF-8";
            allowedMediaTypes = TEXT_MEDIA_TYPES;
            extensionLength = 4;
        } else {
            throw new IllegalArgumentException("仅支持 .md、.markdown 和 .txt 文件");
        }
        String declared = baseMediaType(declaredMediaType);
        if (declared != null && !allowedMediaTypes.contains(declared)) {
            throw new IllegalArgumentException("上传文件媒体类型与扩展名不匹配");
        }
        String title = requestedTitle == null || requestedTitle.isBlank()
                ? fileName.substring(0, fileName.length() - extensionLength) : requestedTitle;
        return new FileMetadata(required(title, "文档标题", 160), inputType,
                fileName, mediaType);
    }

    /** 删除客户端路径信息并校验安全文件名长度。 */
    private String safeFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            throw new IllegalArgumentException("上传文件名不能为空");
        }
        String normalized = originalFileName.replace('\\', '/');
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (fileName.isBlank() || fileName.length() > 255 || fileName.equals(".") || fileName.equals("..")) {
            throw new IllegalArgumentException("上传文件名不合法");
        }
        return fileName;
    }

    /** 从可能带 charset 的声明值中取得小写基础媒体类型。 */
    private String baseMediaType(String declaredMediaType) {
        if (declaredMediaType == null || declaredMediaType.isBlank()) {
            return null;
        }
        return declaredMediaType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    /** 在数据库唯一约束前提供明确的重复内容错误。 */
    private void requireUniqueContent(String contentHash, Long excludedDocumentId) {
        if (repository.existsActiveContentHash(contentHash, excludedDocumentId)) {
            throw new ApplicationException(ErrorCode.KNOWLEDGE_DUPLICATE_CONTENT,
                    "相同内容的有效文档已经存在");
        }
    }

    /** 校验客户端版本并返回原聚合。 */
    private ManagedDocument requireVersion(ManagedDocument document, long version) {
        if (version < 0 || document.version() != version) {
            throw new ApplicationException(ErrorCode.KNOWLEDGE_VERSION_CONFLICT,
                    "文档版本已变化");
        }
        return document;
    }

    /** 创建具有统一租约和保留期的外部幂等命令。 */
    private IdempotencyCommand command(String operationType, String key, String requestHash) {
        return new IdempotencyCommand(operator(), operationType,
                required(key, "幂等键", 160), requestHash,
                IDEMPOTENCY_LEASE, IDEMPOTENCY_RETENTION);
    }

    /** 校验并去除必填文本首尾空白。 */
    private String required(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }

    /** 返回服务端固定操作者标识。 */
    private String operator() {
        return operatorProvider.currentOperator().value();
    }

    /** 保存服务端确认的上传文件元数据。 */
    private record FileMetadata(String title, DocumentInputType inputType,
                                String fileName, String mediaType) {
    }
}
