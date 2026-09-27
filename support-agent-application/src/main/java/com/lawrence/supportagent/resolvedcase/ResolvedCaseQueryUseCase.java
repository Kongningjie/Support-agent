package com.lawrence.supportagent.resolvedcase;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.resolvedcase.port.ResolvedCaseRepository;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpace;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceAccessService;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceStatus;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceSummary;
import com.lawrence.supportagent.knowledgespace.SpaceRole;
import com.lawrence.supportagent.knowledgespace.port.KnowledgeSpaceRepository;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.ticket.Ticket;
import com.lawrence.supportagent.ticket.port.TicketRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 编排案例详情和受控分页查询。 */
public class ResolvedCaseQueryUseCase {
    private final ResolvedCaseRepository cases;
    private final TicketRepository tickets;
    private final KnowledgeSpaceRepository spaces;
    private final KnowledgeSpaceAccessService access;

    /** 注入案例及来源工单仓储。 */
    public ResolvedCaseQueryUseCase(ResolvedCaseRepository cases, TicketRepository tickets,
                                    KnowledgeSpaceRepository spaces,
                                    KnowledgeSpaceAccessService access) {
        this.cases = cases;
        this.tickets = tickets;
        this.spaces = spaces;
        this.access = access;
    }

    /** 按公开案例 ID 查询完整详情。 */
    public ResolvedCaseDetails get(AuthenticatedUser actor, long caseId) {
        ResolvedCase value = requireCase(caseId);
        boolean includeSource = authorizeRead(actor, value);
        return details(value, includeSource);
    }

    /** 按状态、工单号和关键词返回案例摘要页。 */
    public ResolvedCasePage page(AuthenticatedUser actor, ResolvedCaseStatus status,
                                 String sourceTicketNo, String keyword, UUID spaceId,
                                 int page, int size) {
        if (page < 1 || size < 1 || size > 100 || page - 1 > Integer.MAX_VALUE / size) {
            throw new IllegalArgumentException("案例分页参数不合法");
        }
        String normalizedTicketNo = optional(sourceTicketNo, 13);
        String normalizedKeyword = optional(keyword, 160);
        int offset = (page - 1) * size;
        Scope scope = scope(actor, spaceId);
        List<ResolvedCaseSummary> items = cases.findPage(status, normalizedTicketNo,
                        normalizedKeyword, scope.spaceIds, scope.publishedOnly, offset, size).stream()
                .map(value -> new ResolvedCaseSummary(value.id(),
                        value.spaceId(),
                        spaceSummary(value.spaceId()),
                        scope.publishedOnly ? null : requireTicket(value.sourceTicketId()).ticketNo(), value.title(),
                        value.status(), value.version(), value.createdAt(), value.updatedAt()))
                .toList();
        long total = cases.count(status, normalizedTicketNo, normalizedKeyword,
                scope.spaceIds, scope.publishedOnly);
        long pages = total == 0 ? 0 : (total - 1) / size + 1;
        return new ResolvedCasePage(items, page, size, total,
                pages > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) pages);
    }

    /** 读取案例聚合，不存在时返回统一知识错误。 */
    public ResolvedCase requireCase(long caseId) {
        if (caseId <= 0) {
            throw new IllegalArgumentException("案例 ID 必须为正整数");
        }
        return cases.findById(caseId).filter(value -> !value.deleted()).orElseThrow(() ->
                new ApplicationException(ErrorCode.KNOWLEDGE_NOT_FOUND, "已解决案例不存在"));
    }

    /** 返回带真实空间摘要的内部案例详情。 */
    public ResolvedCaseDetails details(ResolvedCase value, boolean includeSourceTicket) {
        return ResolvedCaseDetails.from(value, requireTicket(value.sourceTicketId()),
                spaceSummary(value.spaceId()), includeSourceTicket);
    }

    /** 按发布态或草稿态执行读取授权并返回是否允许暴露来源工单摘要。 */
    private boolean authorizeRead(AuthenticatedUser actor, ResolvedCase value) {
        if (value.status() == ResolvedCaseStatus.PUBLISHED) {
            access.requireReadable(actor, value.spaceId());
            SpaceRole role = access.currentRole(actor, value.spaceId());
            return actor.administrator() || role != null && role.includes(SpaceRole.EDITOR);
        }
        access.requireRoleForMetadata(actor, value.spaceId(), SpaceRole.EDITOR);
        return true;
    }

    /** 构造案例分页授权空间和发布态限制。 */
    private Scope scope(AuthenticatedUser actor, UUID requestedSpaceId) {
        if (requestedSpaceId == null) {
            return new Scope(access.readableActiveSpaceIds(actor), !actor.administrator());
        }
        KnowledgeSpace space = access.requireReadable(actor, requestedSpaceId);
        SpaceRole role = access.currentRole(actor, requestedSpaceId);
        boolean publishedOnly = !actor.administrator()
                && (space.status() != KnowledgeSpaceStatus.ACTIVE
                || role == null || !role.includes(SpaceRole.EDITOR));
        return new Scope(Set.of(requestedSpaceId), publishedOnly);
    }

    /** 读取案例所属空间的最小摘要。 */
    private KnowledgeSpaceSummary spaceSummary(UUID spaceId) {
        KnowledgeSpace space = spaces.findBySpaceId(spaceId).orElseThrow(() ->
                new IllegalStateException("案例所属知识空间不存在"));
        return KnowledgeSpaceSummary.from(space);
    }

    /** 读取案例关联工单并拒绝损坏的关联数据。 */
    private Ticket requireTicket(long ticketId) {
        return tickets.findById(ticketId).orElseThrow(() ->
                new IllegalStateException("案例来源工单不存在"));
    }

    /** 规整可空分页过滤文本。 */
    private String optional(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw new IllegalArgumentException("案例查询条件过长");
        return normalized;
    }

    /** 保存案例列表已经授权的空间集合与发布态限制。 */
    private record Scope(Set<UUID> spaceIds, boolean publishedOnly) { }
}
