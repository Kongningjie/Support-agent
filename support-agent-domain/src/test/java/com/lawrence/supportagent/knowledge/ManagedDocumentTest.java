package com.lawrence.supportagent.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 验证托管文档发布与归档规则。 */
class ManagedDocumentTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    /** 验证文档必须经过索引中状态才能发布。 */
    @Test
    void shouldPublishAfterIndexing() {
        ManagedDocument published = document().startIndexing("dev-operator", NOW).publish("dev-operator", NOW);
        assertEquals(ManagedDocumentStatus.PUBLISHED, published.status());
        assertEquals(2, published.version());
    }

    /** 验证已发布文档不能直接修改。 */
    @Test
    void shouldRejectRevisingPublishedDocument() {
        ManagedDocument published = document().startIndexing("dev-operator", NOW).publish("dev-operator", NOW);
        assertThrows(IllegalStateException.class,
                () -> published.revise("新标题", "新正文", "new-hash", "dev-operator", NOW));
    }

    /** 验证索引失败文档可修改后重试，随后发布并归档。 */
    @Test
    void shouldRecoverFailureAndArchivePublishedDocument() {
        ManagedDocument failed = document().startIndexing("indexer", NOW)
                .failIndexing("临时失败", "indexer", NOW.plusSeconds(1));
        ManagedDocument archived = failed.revise("新标题", "新正文", "new-hash", "editor",
                        NOW.plusSeconds(2))
                .startIndexing("indexer", NOW.plusSeconds(3))
                .publish("publisher", NOW.plusSeconds(4))
                .archive("内容过期", "archiver", NOW.plusSeconds(5));

        assertEquals(ManagedDocumentStatus.ARCHIVED, archived.status());
        assertEquals(6, archived.version());
    }

    /** 验证从未发布草稿可以软删除且删除后不能索引。 */
    @Test
    void shouldDeleteOnlyUnpublishedDraft() {
        ManagedDocument deleted = document().deleteDraft("operator", NOW.plusSeconds(1));

        assertTrue(deleted.deleted());
        assertThrows(IllegalStateException.class,
                () -> deleted.startIndexing("operator", NOW.plusSeconds(2)));
    }

    /** 验证文档必填正文和哈希不能为空。 */
    @Test
    void shouldRejectMissingRequiredContent() {
        assertThrows(IllegalArgumentException.class,
                () -> ManagedDocument.draft("标题", DocumentInputType.DIRECT_TEXT,
                        null, "text/plain", " ", "hash", "operator", NOW));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedDocument.draft("标题", DocumentInputType.DIRECT_TEXT,
                        null, "text/plain", "正文", null, "operator", NOW));
    }

    /** 创建供状态测试使用的文档草稿。 */
    private ManagedDocument document() {
        return ManagedDocument.draft("标题", DocumentInputType.DIRECT_TEXT, null, "text/plain",
                "正文", "hash", "dev-operator", NOW);
    }
}
