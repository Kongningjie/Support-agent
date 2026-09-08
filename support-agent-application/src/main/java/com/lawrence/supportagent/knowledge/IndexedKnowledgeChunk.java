package com.lawrence.supportagent.knowledge;

import java.time.Instant;
import java.util.List;

/** 应用层可交给搜索适配器写入的完整知识分块。 */
public record IndexedKnowledgeChunk(String chunkId, String sourceType, long sourceId,
                                    long sourceVersion, int chunkIndex, String title,
                                    String headingPath, String content,
                                    List<ExactTerm> exactTerms, String contentHash,
                                    List<Double> embedding, Instant publishedAt,
                                    Instant indexedAt, String chunkStrategyVersion,
                                    String exactTermExtractorVersion) {
}
