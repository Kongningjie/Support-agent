package com.lawrence.supportagent.knowledge;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpace;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceAccessService;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceStatus;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceSummary;
import com.lawrence.supportagent.knowledgespace.SpaceRole;
import com.lawrence.supportagent.knowledgespace.port.KnowledgeSpaceRepository;
import com.lawrence.supportagent.knowledge.port.ManagedDocumentRepository;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 提供托管文档详情、分页和内部任务定位所需的只读边界。 */
public class ManagedDocumentQueryUseCase {
    private final ManagedDocumentRepository repository;
    private final KnowledgeSpaceRepository spaces;
    private final KnowledgeSpaceAccessService access;

    /** 注入托管文档、空间仓储和统一空间授权服务。 */
    public ManagedDocumentQueryUseCase(ManagedDocumentRepository repository,
                                       KnowledgeSpaceRepository spaces,
                                       KnowledgeSpaceAccessService access) {
        this.repository = repository;
        this.spaces = spaces;
        this.access = access;
    }

    /** 按公开十进制文档 ID 查询当前主体有权获知的详情。 */
    public ManagedDocumentDetails get(AuthenticatedUser actor, long documentId) {
        ManagedDocument value = requireDocument(documentId);
        authorizeRead(actor, value);
        return details(value);
    }

    /** 按空间、状态和关键词查询授权范围内不含正文的分页摘要。 */
    public ManagedDocumentPage page(AuthenticatedUser actor, ManagedDocumentStatus status,
                                    String keyword, UUID spaceId, int page, int size) {
        if (page < 1 || size < 1 || size > 100 || page - 1 > Integer.MAX_VALUE / size) {
            throw new IllegalArgumentException("文档分页参数不合法");
        }
        String normalizedKeyword = normalizeKeyword(keyword);
        Scope scope = scope(actor, spaceId);
        long total = repository.count(status, normalizedKeyword, scope.spaceIds,
                scope.publishedOnly);
        List<ManagedDocumentSummary> items = repository.findPage(status, normalizedKeyword,
                scope.spaceIds, scope.publishedOnly, (page - 1) * size, size).stream()
                .map(this::summary).toList();
        long pages = total == 0 ? 0 : (total - 1) / size + 1;
        return new ManagedDocumentPage(items, page, size, total,
                pages > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) pages);
    }

    /** 按内部 ID 查询可用文档，供幂等重放和任务编排使用。 */
    public ManagedDocument requireDocument(long documentId) {
        if (documentId <= 0) {
            throw new IllegalArgumentException("文档 ID 必须为正整数");
        }
        return repository.findById(documentId)
                .filter(value -> !value.deleted())
                .orElseThrow(() -> new ApplicationException(
                        ErrorCode.KNOWLEDGE_NOT_FOUND, "托管文档不存在"));
    }

    /** 创建携带真实空间摘要的详情，供命令用例安全重放。 */
    public ManagedDocumentDetails details(ManagedDocument value) {
        return ManagedDocumentDetails.from(value, summary(value.spaceId()));
    }

    /** 对单个文档执行发布态读取或草稿编辑态读取授权。 */
    private void authorizeRead(AuthenticatedUser actor, ManagedDocument value) {
        if (value.status() == ManagedDocumentStatus.PUBLISHED) {
            access.requireReadable(actor, value.spaceId());
            return;
        }
        access.requireRoleForMetadata(actor, value.spaceId(), SpaceRole.EDITOR);
    }

    /** 构造列表允许空间和是否仅能查看发布态的约束。 */
    private Scope scope(AuthenticatedUser actor, UUID requestedSpaceId) {
        if (requestedSpaceId == null) {
            return new Scope(access.readableActiveSpaceIds(actor), !actor.administrator());
        }
        KnowledgeSpace space = access.requireReadable(actor, requestedSpaceId);
        boolean publishedOnly = !actor.administrator()
                && (space.status() != KnowledgeSpaceStatus.ACTIVE
                || access.currentRole(actor, requestedSpaceId) == null
                || !access.currentRole(actor, requestedSpaceId).includes(SpaceRole.EDITOR));
        return new Scope(Set.of(requestedSpaceId), publishedOnly);
    }

    /** 读取真实空间并生成最小摘要；损坏的外键事实直接失败。 */
    private KnowledgeSpaceSummary summary(UUID spaceId) {
        return KnowledgeSpaceSummary.from(spaces.findBySpaceId(spaceId).orElseThrow(() ->
                new IllegalStateException("文档所属知识空间不存在")));
    }

    /** 从文档生成带真实空间摘要的分页视图。 */
    private ManagedDocumentSummary summary(ManagedDocument value) {
        return ManagedDocumentSummary.from(value, summary(value.spaceId()));
    }

    /** 为查询规整可空关键词并限制长度。 */
    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String normalized = keyword.trim();
        if (normalized.length() > 160) {
            throw new IllegalArgumentException("查询关键词不能超过 160 个字符");
        }
        return normalized;
    }

    /** 保存一次列表查询已经授权的空间集合与发布态限制。 */
    private record Scope(Set<UUID> spaceIds, boolean publishedOnly) { }
}
