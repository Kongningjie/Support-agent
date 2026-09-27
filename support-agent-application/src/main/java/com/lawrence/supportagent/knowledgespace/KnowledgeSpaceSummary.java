package com.lawrence.supportagent.knowledgespace;

import java.util.UUID;

/**
 * 供既有资源响应复用且不泄露治理信息的最小空间摘要。
 *
 * @param spaceId 知识空间稳定 UUID
 * @param code 知识空间稳定业务编码
 * @param name 知识空间展示名称
 */
public record KnowledgeSpaceSummary(UUID spaceId, String code, String name) {
    /** 从经过授权读取的空间聚合创建最小摘要。 */
    public static KnowledgeSpaceSummary from(KnowledgeSpace value) {
        if (value == null) throw new IllegalArgumentException("知识空间不能为空");
        return new KnowledgeSpaceSummary(value.spaceId(), value.code(), value.name());
    }

    /** 返回阶段 18 既有资源统一归属的 GLOBAL 空间摘要。 */
    public static KnowledgeSpaceSummary global(UUID actualSpaceId) {
        if (!KnowledgeSpace.GLOBAL_SPACE_ID.equals(actualSpaceId)) {
            throw new IllegalStateException("阶段 18 既有资源只能归属 GLOBAL 空间");
        }
        return new KnowledgeSpaceSummary(actualSpaceId, KnowledgeSpace.GLOBAL_CODE, "企业公共空间");
    }
}
