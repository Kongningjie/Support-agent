package com.lawrence.supportagent.knowledge.port;

import com.lawrence.supportagent.knowledge.IndexedKnowledgeChunk;
import java.util.List;

/** 隔离版本化 Elasticsearch 索引、完整性校验和来源删除能力。 */
public interface KnowledgeIndexPort {
    /** 幂等创建并校验物理索引、ICU 分析器和业务别名。 */
    void ensureReady();

    /** 使用 Bulk API 写入一个来源版本的全部分块并逐项校验结果。 */
    void indexChunks(List<IndexedKnowledgeChunk> chunks);

    /** 校验指定来源版本的分块数量和内容哈希与预期完全一致。 */
    boolean verifyVersion(String sourceType, long sourceId, long sourceVersion,
                          List<String> expectedContentHashes);

    /** 删除一次可能部分写入的来源版本，用于失败清理。 */
    void deleteVersion(String sourceType, long sourceId, long sourceVersion);

    /** 删除一个已归档来源的全部历史分块。 */
    void deleteSource(String sourceType, long sourceId);

    /** 判断指定来源是否仍存在任何索引分块。 */
    boolean sourceExists(String sourceType, long sourceId);
}
