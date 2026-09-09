package com.lawrence.supportagent.resolvedcase;

import java.util.List;

/**
 * 案例分页查询结果。
 *
 * @param items 当前页案例摘要
 * @param page 从一开始的当前页码
 * @param size 每页请求数量
 * @param totalElements 满足条件的案例总数
 * @param totalPages 按当前页大小计算的总页数
 */
public record ResolvedCasePage(List<ResolvedCaseSummary> items, int page, int size,
                               long totalElements, int totalPages) {
}
