package com.lawrence.supportagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.knowledge.port.ManagedDocumentRepository;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpace;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceStatus;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceVisibility;
import com.lawrence.supportagent.knowledgespace.port.KnowledgeSpaceRepository;
import com.lawrence.supportagent.resolvedcase.port.ResolvedCaseRepository;
import com.lawrence.supportagent.retrieval.port.KnowledgeSourceValidityPort.SourceVersion;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 验证 MySQL 来源回查独立阻断伪造空间和停用空间候选。 */
class MySqlKnowledgeSourceValidityAdapterTest {
    private static final Instant NOW = Instant.parse("2026-09-27T08:00:00Z");

    /** 只有版本、来源空间、允许集合和空间状态同时匹配的候选才有效。 */
    @Test
    void shouldRequireMatchingActiveSourceSpace() {
        UUID actualSpaceId = UUID.randomUUID();
        UUID forgedSpaceId = UUID.randomUUID();
        UUID disabledSpaceId = UUID.randomUUID();
        ManagedDocument document = new ManagedDocument(7L, actualSpaceId, "连接故障",
                DocumentInputType.DIRECT_TEXT, null, "text/plain", "检查连接。",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                ManagedDocumentStatus.PUBLISHED, 2, null, null, false, null, null,
                "editor", NOW, "manager", NOW, "manager", NOW, null, null);
        ManagedDocumentRepository documents = mock(ManagedDocumentRepository.class);
        when(documents.findById(7L)).thenReturn(Optional.of(document));
        KnowledgeSpaceRepository spaces = mock(KnowledgeSpaceRepository.class);
        when(spaces.findBySpaceId(actualSpaceId)).thenReturn(Optional.of(
                space(actualSpaceId, "ACTUAL", KnowledgeSpaceStatus.ACTIVE)));
        when(spaces.findBySpaceId(forgedSpaceId)).thenReturn(Optional.of(
                space(forgedSpaceId, "FORGED", KnowledgeSpaceStatus.ACTIVE)));
        when(spaces.findBySpaceId(disabledSpaceId)).thenReturn(Optional.of(
                space(disabledSpaceId, "DISABLED", KnowledgeSpaceStatus.DISABLED)));
        MySqlKnowledgeSourceValidityAdapter adapter = new MySqlKnowledgeSourceValidityAdapter(
                documents, mock(ResolvedCaseRepository.class), spaces);
        SourceVersion valid = new SourceVersion("MANAGED_DOCUMENT", 7L, 2L, actualSpaceId);
        SourceVersion forged = new SourceVersion("MANAGED_DOCUMENT", 7L, 2L, forgedSpaceId);
        SourceVersion disabled = new SourceVersion("MANAGED_DOCUMENT", 7L, 2L, disabledSpaceId);

        Set<SourceVersion> result = adapter.findValid(List.of(valid, forged, disabled),
                Set.of(actualSpaceId, forgedSpaceId, disabledSpaceId));

        assertThat(result).containsExactly(valid);
    }

    /** 创建指定状态的非系统受限空间。 */
    private KnowledgeSpace space(UUID spaceId, String code, KnowledgeSpaceStatus status) {
        return new KnowledgeSpace(1L, spaceId, code, code, null,
                KnowledgeSpaceVisibility.RESTRICTED, status, false, 0,
                "admin", NOW, "admin", NOW);
    }
}
