package com.lawrence.supportagent.knowledgespace;

import java.util.List;

/** 知识空间稳定分页结果。 */
public record KnowledgeSpacePage(List<KnowledgeSpaceView> items, int page, int size, long total) {
    /** 防止调用方修改返回集合。 */
    public KnowledgeSpacePage {
        items = List.copyOf(items);
    }
}
