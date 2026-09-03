package com.lawrence.supportagent.resolvedcase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 验证已解决案例的发布、拒绝和归档状态规则。 */
class ResolvedCaseTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    /** 验证案例经过发布中状态后才能发布并归档。 */
    @Test
    void shouldPublishAndArchiveCase() {
        ResolvedCase archived = draft().startPublishing("dev-operator", NOW)
                .publish("dev-operator", NOW).archive("内容过期", "dev-operator", NOW);
        assertEquals(ResolvedCaseStatus.ARCHIVED, archived.status());
        assertEquals(3, archived.version());
    }

    /** 验证已拒绝案例不能再次发布。 */
    @Test
    void shouldRejectPublishingRejectedCase() {
        ResolvedCase rejected = draft().reject("不适合沉淀", "dev-operator", NOW);
        assertThrows(IllegalStateException.class,
                () -> rejected.startPublishing("dev-operator", NOW));
    }

    /** 验证发布失败案例可以重新进入发布流程并成功发布。 */
    @Test
    void shouldRetryFailedPublishing() {
        ResolvedCase failed = draft().startPublishing("publisher", NOW)
                .failPublishing("索引失败", "publisher", NOW.plusSeconds(1));
        ResolvedCase published = failed.startPublishing("publisher", NOW.plusSeconds(2))
                .publish("publisher", NOW.plusSeconds(3));

        assertEquals(ResolvedCaseStatus.PUBLISHED, published.status());
        assertEquals(4, published.version());
    }

    /** 验证非草稿不能拒绝，非已发布案例不能归档。 */
    @Test
    void shouldRejectInvalidReviewTransitions() {
        ResolvedCase publishing = draft().startPublishing("publisher", NOW);

        assertThrows(IllegalStateException.class,
                () -> publishing.reject("拒绝", "reviewer", NOW));
        assertThrows(IllegalStateException.class,
                () -> publishing.archive("归档", "reviewer", NOW));
    }

    /** 验证案例来源主键和核心事实字段必须有效。 */
    @Test
    void shouldRejectMissingRequiredFacts() {
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedCase(null, 0, "案例", "问题", "根因", "方案",
                        ResolvedCaseStatus.DRAFT, "hash", 0, null, null, null,
                        false, null, null, "system", NOW, "system", NOW,
                        null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedCase(null, 1, "案例", "问题", " ", "方案",
                        ResolvedCaseStatus.DRAFT, "hash", 0, null, null, null,
                        false, null, null, "system", NOW, "system", NOW,
                        null, null, null, null));
    }

    /** 创建测试所需的待审核案例。 */
    private ResolvedCase draft() {
        return new ResolvedCase(null, 1, "案例", "问题", "根因", "方案",
                ResolvedCaseStatus.DRAFT, "hash", 0, null, null, null, false,
                null, null, "system", NOW, "system", NOW, null, null, null, null);
    }
}
