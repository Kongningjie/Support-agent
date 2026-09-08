package com.lawrence.supportagent.retrieval.port;

import java.util.List;
import java.util.Set;

/** 批量回查 MySQL，确保索引候选仍对应当前已发布来源版本。 */
public interface KnowledgeSourceValidityPort {
    /** 返回输入引用中仍有效的来源版本集合。 */
    Set<SourceVersion> findValid(List<SourceVersion> sources);

    /** @param sourceType 来源类型 @param sourceId 来源 ID @param sourceVersion 来源版本 */
    record SourceVersion(String sourceType, long sourceId, long sourceVersion) { }
}
