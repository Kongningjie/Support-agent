package com.lawrence.supportagent.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证标题路径、确定性哈希以及超长正文重叠分块。 */
class DocumentChunkerTest {
    private final DocumentChunker chunker = new DocumentChunker(new ExactTermExtractor());

    /** 验证 Markdown 标题层级被带入对应分块上下文。 */
    @Test
    void shouldPreserveMarkdownHeadingPath() {
        List<KnowledgeChunkDraft> chunks = chunker.chunk("手册", DocumentInputType.MARKDOWN_FILE,
                "# 数据库\n检查连接。\n\n## MySQL\n执行命令：\n`mysql --version`");

        assertEquals(2, chunks.size());
        assertEquals("手册 > 数据库", chunks.get(0).headingPath());
        assertEquals("手册 > 数据库 > MySQL", chunks.get(1).headingPath());
        assertTrue(chunks.get(1).exactTerms().stream()
                .anyMatch(term -> term.type() == ExactTermType.COMMAND));
    }

    /** 验证相同输入产生完全相同的分块与哈希。 */
    @Test
    void shouldBeDeterministic() {
        String content = "第一段。\n\n第二段。";

        assertEquals(chunker.chunk("说明", DocumentInputType.TEXT_FILE, content),
                chunker.chunk("说明", DocumentInputType.TEXT_FILE, content));
    }

    /** 验证超长单元不超过 1200 码点并保留 120 码点重叠。 */
    @Test
    void shouldSplitLongUnitWithOverlap() {
        List<KnowledgeChunkDraft> chunks = chunker.chunk("说明", DocumentInputType.DIRECT_TEXT,
                "甲".repeat(1300));

        assertEquals(2, chunks.size());
        assertEquals(1200, chunks.get(0).content().codePointCount(0, chunks.get(0).content().length()));
        assertEquals(220, chunks.get(1).content().codePointCount(0, chunks.get(1).content().length()));
    }
}
