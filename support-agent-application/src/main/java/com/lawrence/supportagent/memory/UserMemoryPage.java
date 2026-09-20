package com.lawrence.supportagent.memory;

import java.util.List;

/** 当前用户长期记忆的稳定分页结果。 */
public record UserMemoryPage(List<UserMemoryView> items, int page, int size,
                             long totalElements, int totalPages) {
    /** 防止调用方修改分页集合并校验统计字段。 */
    public UserMemoryPage {
        items = items == null ? List.of() : List.copyOf(items);
        if (page < 1 || size < 1 || totalElements < 0 || totalPages < 0) {
            throw new IllegalArgumentException("记忆分页字段不合法");
        }
    }
}
