package com.lawrence.supportagent.knowledge.port;

import com.lawrence.supportagent.knowledge.ManagedDocument;
import java.util.Optional;
import java.util.List;
import com.lawrence.supportagent.knowledge.ManagedDocumentStatus;

/** 定义托管文档聚合的持久化边界。 */
public interface ManagedDocumentRepository {
    /** 按内部主键查询托管文档。 */
    Optional<ManagedDocument> findById(long id);

    /** 新增或更新托管文档并返回持久化后的聚合。 */
    ManagedDocument save(ManagedDocument document);

    /** 按状态、关键词和固定排序查询未删除文档。 */
    List<ManagedDocument> findPage(ManagedDocumentStatus status, String keyword,
                                   int offset, int size);

    /** 统计与分页条件一致的未删除文档数量。 */
    long count(ManagedDocumentStatus status, String keyword);

    /** 判断指定规范化正文哈希是否已被其他有效文档占用。 */
    boolean existsActiveContentHash(String contentHash, Long excludedDocumentId);
}
