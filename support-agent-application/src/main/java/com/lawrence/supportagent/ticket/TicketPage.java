package com.lawrence.supportagent.ticket;

import java.util.List;

/**
 * 应用层工单分页结果。
 *
 * @param items 当前页工单摘要
 * @param page 从 1 开始的页码
 * @param size 每页数量
 * @param totalElements 总记录数
 * @param totalPages 总页数
 */
public record TicketPage(List<TicketSummary> items, int page, int size,
                         long totalElements, int totalPages) {
}
