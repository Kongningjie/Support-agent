package com.lawrence.supportagent.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.asynctask.AsyncTaskCreator;
import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.asynctask.port.AsyncTaskRepository;
import com.lawrence.supportagent.idempotency.IdempotencyCommand;
import com.lawrence.supportagent.idempotency.IdempotentExecutor;
import com.lawrence.supportagent.idempotency.IdempotentResource;
import com.lawrence.supportagent.sharedkernel.OperatorId;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.knowledge.port.ManagedDocumentRepository;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpace;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceAccessService;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceStatus;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceVisibility;
import com.lawrence.supportagent.knowledgespace.SpaceRole;
import com.lawrence.supportagent.knowledgespace.port.KnowledgeSpaceRepository;
import com.lawrence.supportagent.user.UserRole;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 验证托管文档文件导入元数据和创建期安全门禁。 */
class ManagedDocumentCommandUseCaseTest {
    private static final Instant NOW = Instant.parse("2026-09-07T01:00:00Z");
    private static final AuthenticatedUser ACTOR = new AuthenticatedUser(
            UUID.randomUUID(), "tester", UserRole.ADMIN);

    /** 验证标题缺失时使用安全文件名主体并由服务端确认媒体类型。 */
    @Test
    void shouldDeriveTitleAndMediaTypeFromMarkdownFileName() {
        ManagedDocumentCommandUseCase useCase = useCase();

        ManagedDocumentDetails result = useCase.createFile(ACTOR, null, null,
                "C:\\upload\\排障手册.md",
                "text/markdown", "# 排障\n检查连接".getBytes(StandardCharsets.UTF_8), "file-001");

        assertEquals("排障手册", result.title());
        assertEquals("排障手册.md", result.originalFileName());
        assertEquals(DocumentInputType.MARKDOWN_FILE, result.inputType());
        assertEquals("text/markdown;charset=UTF-8", result.mediaType());
    }

    /** 验证不支持扩展名和不匹配媒体类型均在持久化前拒绝。 */
    @Test
    void shouldRejectUnsupportedOrMismatchedFile() {
        ManagedDocumentCommandUseCase useCase = useCase();

        assertThrows(IllegalArgumentException.class, () -> useCase.createFile(ACTOR, null, null,
                "manual.pdf", "application/pdf", "text".getBytes(StandardCharsets.UTF_8), "file-002"));
        assertThrows(IllegalArgumentException.class, () -> useCase.createFile(ACTOR, null, null,
                "manual.txt", "application/json", "text".getBytes(StandardCharsets.UTF_8), "file-003"));
    }

    /** 验证疑似凭据在直接文本创建阶段返回稳定安全错误。 */
    @Test
    void shouldRejectSensitiveTextBeforeSave() {
        ManagedDocumentCommandUseCase useCase = useCase();

        ApplicationException exception = assertThrows(ApplicationException.class,
                () -> useCase.createText(ACTOR, null, "配置",
                        "password=super-secret-value", "text-001"));

        assertEquals(ErrorCode.KNOWLEDGE_SENSITIVE_CONTENT, exception.errorCode());
    }

    /** 创建立即执行幂等动作且自动分配文档 ID 的测试用例。 */
    private ManagedDocumentCommandUseCase useCase() {
        ManagedDocumentRepository repository = mock(ManagedDocumentRepository.class);
        when(repository.existsActiveContentHash(any(), any(), any())).thenReturn(false);
        when(repository.save(any())).thenAnswer(invocation -> assignId(invocation.getArgument(0)));
        when(repository.findById(1L)).thenReturn(Optional.empty());
        KnowledgeSpaceRepository spaces = mock(KnowledgeSpaceRepository.class);
        KnowledgeSpace global = globalSpace();
        when(spaces.findBySpaceId(KnowledgeSpace.GLOBAL_SPACE_ID)).thenReturn(Optional.of(global));
        KnowledgeSpaceAccessService access = mock(KnowledgeSpaceAccessService.class);
        when(access.requireRole(any(), any(), any())).thenReturn(global);
        AsyncTaskRepository taskRepository = mock(AsyncTaskRepository.class);
        return new ManagedDocumentCommandUseCase(repository, taskRepository,
                new ManagedDocumentQueryUseCase(repository, spaces, access),
                new AsyncTaskCreator(taskRepository, () -> NOW), immediateExecutor(),
                () -> new OperatorId("tester"), () -> NOW, new DocumentContentPolicy(), access);
    }

    /** 创建无需基础设施即可执行首次动作的幂等测试替身。 */
    private IdempotentExecutor immediateExecutor() {
        return new IdempotentExecutor() {
            /** {@inheritDoc} */
            @Override
            public <T> T execute(IdempotencyCommand command,
                                 Supplier<IdempotentResource<T>> action,
                                 LongFunction<T> replayLoader) {
                return action.get().value();
            }
        };
    }

    /** 为首次保存的不可变文档复制数据库分配的测试 ID。 */
    private ManagedDocument assignId(ManagedDocument value) {
        if (value.id() != null) {
            return value;
        }
        return new ManagedDocument(1L, value.spaceId(), value.title(), value.inputType(), value.originalFileName(),
                value.mediaType(), value.rawContent(), value.contentHash(), value.status(),
                value.version(), value.indexFailureReason(), value.archiveReason(), value.deleted(),
                value.deletedBy(), value.deletedAt(), value.createdBy(), value.createdAt(),
                value.updatedBy(), value.updatedAt(), value.publishedBy(), value.publishedAt(),
                value.archivedBy(), value.archivedAt());
    }

    /** 创建稳定的系统 GLOBAL 空间测试事实。 */
    private KnowledgeSpace globalSpace() {
        return new KnowledgeSpace(1L, KnowledgeSpace.GLOBAL_SPACE_ID, "GLOBAL", "企业公共知识",
                null, KnowledgeSpaceVisibility.ENTERPRISE, KnowledgeSpaceStatus.ACTIVE,
                true, 0, "system", NOW, "system", NOW);
    }
}
