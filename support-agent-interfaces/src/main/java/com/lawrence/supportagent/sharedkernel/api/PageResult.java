package com.lawrence.supportagent.sharedkernel.api;

import java.util.List;

/**
 * 表示从 1 开始计数的稳定分页结果。
 *
 * @param items 当前页元素的只读副本
 * @param page 从 1 开始的当前页码
 * @param size 每页条数，范围为 1 至 100
 * @param totalElements 符合条件的元素总数
 * @param totalPages 按当前页大小计算的总页数
 * @param <T> 元素类型
 */
public record PageResult<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
    /** 防止分页集合为空引用及分页数字越界。 */
    public PageResult {
        items = items == null ? List.of() : List.copyOf(items);
        if (page < 1 || size < 1 || size > 100 || totalElements < 0 || totalPages < 0) {
            throw new IllegalArgumentException("分页参数不满足约束");
        }
    }
}
