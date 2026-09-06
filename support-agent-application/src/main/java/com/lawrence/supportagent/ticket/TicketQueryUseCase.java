package com.lawrence.supportagent.ticket;

import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.ticket.port.TicketRepository;
import java.util.List;
import java.util.regex.Pattern;

/** 提供 REST 与后续 Agent 工具共用的只读、脱敏工单查询边界。 */
public class TicketQueryUseCase {
    private static final Pattern TICKET_NUMBER = Pattern.compile("T\\d{12}");
    private final TicketRepository repository;

    /** 使用工单持久化端口创建查询用例。 */
    public TicketQueryUseCase(TicketRepository repository) {
        this.repository = repository;
    }

    /** 按公开编号查询不包含内部主键的工单详情。 */
    public TicketDetails get(String ticketNo) {
        return TicketDetails.from(requireTicket(ticketNo));
    }

    /** 按稳定排序、状态和关键词返回一页工单摘要。 */
    public TicketPage page(TicketStatus status, String keyword, int page, int size) {
        if (page < 1 || size < 1 || size > 100 || page - 1 > Integer.MAX_VALUE / size) {
            throw new IllegalArgumentException("页码必须从 1 开始且每页数量为 1 至 100");
        }
        String normalizedKeyword = normalizeOptional(keyword);
        int offset = (page - 1) * size;
        long total = repository.count(status, normalizedKeyword);
        List<TicketSummary> items = repository.findPage(status, normalizedKeyword, offset, size)
                .stream().map(TicketSummary::from).toList();
        long pages = total == 0 ? 0 : (total - 1) / size + 1;
        return new TicketPage(items, page, size, total,
                pages > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) pages);
    }

    /** 按内部主键重查幂等操作首次关联的工单。 */
    public TicketDetails getByInternalId(long id) {
        return TicketDetails.from(repository.findById(id).orElseThrow(this::notFound));
    }

    /** 校验公开编号格式并读取工单聚合。 */
    Ticket requireTicket(String ticketNo) {
        if (ticketNo == null || !TICKET_NUMBER.matcher(ticketNo).matches()) {
            throw new IllegalArgumentException("工单编号格式必须为 T 加 12 位数字");
        }
        return repository.findByTicketNo(ticketNo).orElseThrow(this::notFound);
    }

    /** 把可空关键词规整为空或去除首尾空白后的值。 */
    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 160) {
            throw new IllegalArgumentException("查询关键词不能超过 160 个字符");
        }
        return normalized;
    }

    /** 创建不泄露内部查询细节的工单不存在异常。 */
    private ApplicationException notFound() {
        return new ApplicationException(ErrorCode.TICKET_NOT_FOUND, "工单不存在");
    }
}
