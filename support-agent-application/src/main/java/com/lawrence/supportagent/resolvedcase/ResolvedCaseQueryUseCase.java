package com.lawrence.supportagent.resolvedcase;

import com.lawrence.supportagent.resolvedcase.port.ResolvedCaseRepository;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.ticket.Ticket;
import com.lawrence.supportagent.ticket.port.TicketRepository;
import java.util.List;

/** 编排案例详情和受控分页查询。 */
public class ResolvedCaseQueryUseCase {
    private final ResolvedCaseRepository cases;
    private final TicketRepository tickets;

    /** 注入案例及来源工单仓储。 */
    public ResolvedCaseQueryUseCase(ResolvedCaseRepository cases, TicketRepository tickets) {
        this.cases = cases;
        this.tickets = tickets;
    }

    /** 按公开案例 ID 查询完整详情。 */
    public ResolvedCaseDetails get(long caseId) {
        ResolvedCase value = requireCase(caseId);
        return ResolvedCaseDetails.from(value, requireTicket(value.sourceTicketId()));
    }

    /** 按状态、工单号和关键词返回案例摘要页。 */
    public ResolvedCasePage page(ResolvedCaseStatus status, String sourceTicketNo,
                                 String keyword, int page, int size) {
        if (page < 1 || size < 1 || size > 100 || page - 1 > Integer.MAX_VALUE / size) {
            throw new IllegalArgumentException("案例分页参数不合法");
        }
        String normalizedTicketNo = optional(sourceTicketNo, 13);
        String normalizedKeyword = optional(keyword, 160);
        int offset = (page - 1) * size;
        List<ResolvedCaseSummary> items = cases.findPage(status, normalizedTicketNo,
                        normalizedKeyword, offset, size).stream()
                .map(value -> new ResolvedCaseSummary(value.id(),
                        requireTicket(value.sourceTicketId()).ticketNo(), value.title(),
                        value.status(), value.version(), value.createdAt(), value.updatedAt()))
                .toList();
        long total = cases.count(status, normalizedTicketNo, normalizedKeyword);
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
}
