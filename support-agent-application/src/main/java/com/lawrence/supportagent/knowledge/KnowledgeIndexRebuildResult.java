package com.lawrence.supportagent.knowledge;

/**
 * 全量重建完成后的低敏完整性摘要。
 *
 * @param sourceCount 参与重建且通过完整性校验的知识来源总数
 * @param documentCount 参与重建的已发布文档数
 * @param caseCount 参与重建的已发布案例数
 * @param chunkCount 写入目标索引且通过精确计数校验的分块数
 * @param aliasActivated 是否已完成检索别名原子切换
 */
public record KnowledgeIndexRebuildResult(long sourceCount, long documentCount,
                                          long caseCount, long chunkCount,
                                          boolean aliasActivated) {
}
