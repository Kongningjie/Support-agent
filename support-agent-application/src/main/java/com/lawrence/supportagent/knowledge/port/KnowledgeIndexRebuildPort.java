package com.lawrence.supportagent.knowledge.port;

import com.lawrence.supportagent.knowledge.IndexedKnowledgeChunk;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 隔离新物理索引全量重建、完整性核对和原子别名切换能力。 */
public interface KnowledgeIndexRebuildPort {
    /** 幂等创建并校验配置的新物理索引，但不改变当前业务别名。 */
    void prepareTarget();

    /** 将完整来源版本分块直接写入目标物理索引。 */
    void indexTargetChunks(List<IndexedKnowledgeChunk> chunks);

    /** 校验目标索引中一个来源版本的哈希、空间和分块数量。 */
    boolean verifyTargetVersion(String sourceType, long sourceId, long sourceVersion,
                                UUID spaceId, List<String> expectedContentHashes);

    /** 返回目标物理索引中的总分块数量。 */
    long countTargetChunks();

    /** 验证目标索引全部分块都具有已知且允许的非空空间。 */
    boolean targetSpacesAreKnown(Set<UUID> knownSpaceIds);

    /** 原子地把业务别名从旧索引切换到目标索引，旧索引不删除。 */
    void activateTarget();
}
