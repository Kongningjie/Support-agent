package com.lawrence.supportagent.asynctask;

import java.util.List;

/**
 * 应用层异步任务分页结果。
 *
 * @param items 当前页任务视图
 * @param page 从 1 开始的页码
 * @param size 每页数量
 * @param totalElements 总记录数
 * @param totalPages 总页数
 */
public record AsyncTaskPage(List<AsyncTaskDetails> items, int page, int size,
                            long totalElements, int totalPages) {
}
