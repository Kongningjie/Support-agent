package com.lawrence.supportagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.knowledge.port.KnowledgeIndexRebuildPort;
import com.lawrence.supportagent.knowledge.port.ManagedDocumentRepository;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpace;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceStatus;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceVisibility;
import com.lawrence.supportagent.model.EmbeddingModelPort;
import com.lawrence.supportagent.resolvedcase.ResolvedCaseStatus;
import com.lawrence.supportagent.resolvedcase.port.ResolvedCaseRepository;
import com.lawrence.supportagent.user.UserRole;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 验证全量重建只有在来源、分块和空间完整性全部成立后才切换别名。 */
class KnowledgeIndexRebuildUseCaseTest {
    private static final Instant NOW = Instant.parse("2026-09-27T08:00:00Z");
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(
            UUID.randomUUID(), "admin", UserRole.ADMIN);

    /** 目标索引分块总数与生产分块结果完全一致时才允许原子激活。 */
    @Test
    void shouldActivateAliasAfterExactIntegrityChecks() {
        Fixture fixture = fixture();
        AtomicLong indexedChunks = new AtomicLong();
        doAnswer(invocation -> {
            List<?> chunks = invocation.getArgument(0);
            indexedChunks.addAndGet(chunks.size());
            return null;
        }).when(fixture.index()).indexTargetChunks(anyList());
        when(fixture.index().countTargetChunks()).thenAnswer(ignored -> indexedChunks.get());

        KnowledgeIndexRebuildResult result = fixture.useCase().rebuild(ADMIN);

        assertThat(result.sourceCount()).isEqualTo(1);
        assertThat(result.chunkCount()).isEqualTo(indexedChunks.get());
        assertThat(result.aliasActivated()).isTrue();
        verify(fixture.index()).activateTarget();
    }

    /** 目标索引多出或缺少任何分块都必须阻止别名切换。 */
    @Test
    void shouldNotActivateAliasWhenTotalChunkCountDiffers() {
        Fixture fixture = fixture();
        when(fixture.index().countTargetChunks()).thenReturn(0L);

        assertThatThrownBy(() -> fixture.useCase().rebuild(ADMIN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("知识索引全量重建完整性校验失败");
        verify(fixture.index(), never()).activateTarget();
    }

    /** 创建含一个已发布文档、无案例和一个已知活动空间的重建夹具。 */
    private Fixture fixture() {
        UUID spaceId = UUID.randomUUID();
        ManagedDocument document = new ManagedDocument(7L, spaceId, "连接故障",
                DocumentInputType.DIRECT_TEXT, null, "text/plain",
                "检查 ERROR_CONNECTION 并重新连接。",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                ManagedDocumentStatus.PUBLISHED, 2, null, null, false, null, null,
                "editor", NOW.minusSeconds(60), "manager", NOW, "manager", NOW,
                null, null);
        ManagedDocumentRepository documents = mock(ManagedDocumentRepository.class);
        when(documents.findPage(eq(ManagedDocumentStatus.PUBLISHED), isNull(), anySet(),
                eq(true), eq(0), eq(100))).thenReturn(List.of(document));
        when(documents.count(eq(ManagedDocumentStatus.PUBLISHED), isNull(), anySet(),
                eq(true))).thenReturn(1L);
        ResolvedCaseRepository cases = mock(ResolvedCaseRepository.class);
        when(cases.findPage(eq(ResolvedCaseStatus.PUBLISHED), isNull(), isNull(), anySet(),
                eq(true), anyInt(), eq(100))).thenReturn(List.of());
        when(cases.count(eq(ResolvedCaseStatus.PUBLISHED), isNull(), isNull(), anySet(),
                eq(true))).thenReturn(0L);
        com.lawrence.supportagent.knowledgespace.port.KnowledgeSpaceRepository spaces =
                mock(com.lawrence.supportagent.knowledgespace.port.KnowledgeSpaceRepository.class);
        KnowledgeSpace space = new KnowledgeSpace(1L, spaceId, "PAYMENT", "支付知识", null,
                KnowledgeSpaceVisibility.RESTRICTED, KnowledgeSpaceStatus.ACTIVE, false, 0,
                "admin", NOW, "admin", NOW);
        when(spaces.findAdminPage(isNull(), isNull(), isNull(), eq(0), eq(100)))
                .thenReturn(List.of(space));
        EmbeddingModelPort embeddings = mock(EmbeddingModelPort.class);
        when(embeddings.embedDocuments(anyList(), any())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(ignored -> Collections.nCopies(1024, 0.01D)).toList();
        });
        KnowledgeIndexRebuildPort index = mock(KnowledgeIndexRebuildPort.class);
        when(index.verifyTargetVersion(any(), any(Long.class), any(Long.class),
                any(UUID.class), anyList())).thenReturn(true);
        when(index.targetSpacesAreKnown(anySet())).thenReturn(true);
        KnowledgeIndexRebuildUseCase useCase = new KnowledgeIndexRebuildUseCase(documents,
                cases, spaces, new DocumentChunker(new ExactTermExtractor()), embeddings,
                index, () -> NOW);
        return new Fixture(useCase, index);
    }

    /** 保存待测用例和可验证的索引端口。 */
    private record Fixture(KnowledgeIndexRebuildUseCase useCase,
                           KnowledgeIndexRebuildPort index) { }
}
