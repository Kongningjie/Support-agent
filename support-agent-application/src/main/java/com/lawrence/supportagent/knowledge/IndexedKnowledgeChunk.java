package com.lawrence.supportagent.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 应用层可交给搜索适配器写入的完整知识分块。
 *
 * @param chunkId 稳定分块 ID
 * @param sourceType 来源类型
 * @param sourceId 来源内部主键
 * @param sourceVersion 来源发布版本
 * @param spaceId 来源归属的知识空间 UUID
 * @param chunkIndex 分块在来源内的零基序号
 * @param title 来源标题
 * @param headingPath 分块标题路径
 * @param content 分块正文
 * @param exactTerms 分块提取的精确技术词
 * @param contentHash 分块正文内容哈希
 * @param embedding 分块向量
 * @param publishedAt 来源发布 UTC 时间
 * @param indexedAt 分块构建 UTC 时间
 * @param chunkStrategyVersion 分块策略版本
 * @param exactTermExtractorVersion 精确词提取器版本
 */
public record IndexedKnowledgeChunk(String chunkId, String sourceType, long sourceId,
                                    long sourceVersion, UUID spaceId, int chunkIndex, String title,
                                    String headingPath, String content,
                                    List<ExactTerm> exactTerms, String contentHash,
                                    List<Double> embedding, Instant publishedAt,
                                    Instant indexedAt, String chunkStrategyVersion,
                                    String exactTermExtractorVersion) {
    /** 为既有锁定 GLOBAL 评测语料保留源代码兼容构造器。 */
    public IndexedKnowledgeChunk(String chunkId, String sourceType, long sourceId,
                                 long sourceVersion, int chunkIndex, String title,
                                 String headingPath, String content,
                                 List<ExactTerm> exactTerms, String contentHash,
                                 List<Double> embedding, Instant publishedAt,
                                 Instant indexedAt, String chunkStrategyVersion,
                                 String exactTermExtractorVersion) {
        this(chunkId, sourceType, sourceId, sourceVersion,
                com.lawrence.supportagent.knowledgespace.KnowledgeSpace.GLOBAL_SPACE_ID,
                chunkIndex, title, headingPath, content, exactTerms, contentHash,
                embedding, publishedAt, indexedAt, chunkStrategyVersion,
                exactTermExtractorVersion);
    }
}
