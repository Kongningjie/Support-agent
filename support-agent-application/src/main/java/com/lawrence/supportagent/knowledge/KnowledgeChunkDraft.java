package com.lawrence.supportagent.knowledge;

import java.util.List;

/** 保存向量化之前的确定性知识分块。 */
public record KnowledgeChunkDraft(int chunkIndex, String headingPath, String content,
                                  String contentHash, List<ExactTerm> exactTerms) {
}
