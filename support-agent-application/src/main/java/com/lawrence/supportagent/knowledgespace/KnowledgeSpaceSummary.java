package com.lawrence.supportagent.knowledgespace;

import java.util.UUID;

/** 供既有资源响应复用且不泄露治理信息的最小空间摘要。 */
public record KnowledgeSpaceSummary(UUID spaceId, String code, String name) {
    /** 返回阶段 18 既有资源统一归属的 GLOBAL 空间摘要。 */
    public static KnowledgeSpaceSummary global(UUID actualSpaceId) {
        if (!KnowledgeSpace.GLOBAL_SPACE_ID.equals(actualSpaceId)) {
            throw new IllegalStateException("阶段 18 既有资源只能归属 GLOBAL 空间");
        }
        return new KnowledgeSpaceSummary(actualSpaceId, KnowledgeSpace.GLOBAL_CODE, "企业公共空间");
    }
}
