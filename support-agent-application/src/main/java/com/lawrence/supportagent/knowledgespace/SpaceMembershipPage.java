package com.lawrence.supportagent.knowledgespace;

import java.util.List;

/** 空间成员稳定分页结果。 */
public record SpaceMembershipPage(List<SpaceMembershipView> items, int page, int size, long total) {
    /** 防止调用方修改返回集合。 */
    public SpaceMembershipPage {
        items = List.copyOf(items);
    }
}
