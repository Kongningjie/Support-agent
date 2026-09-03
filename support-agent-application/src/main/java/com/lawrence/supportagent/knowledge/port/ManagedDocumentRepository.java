package com.lawrence.supportagent.knowledge.port;

import com.lawrence.supportagent.knowledge.ManagedDocument;
import java.util.Optional;

/** 定义托管文档聚合的持久化边界。 */
public interface ManagedDocumentRepository {
    /** 按内部主键查询托管文档。 */
    Optional<ManagedDocument> findById(long id);

    /** 新增或更新托管文档并返回持久化后的聚合。 */
    ManagedDocument save(ManagedDocument document);
}
