package com.lawrence.supportagent.auth;

import java.util.List;

/** @param items 当前页安全用户视图 @param page 页码，从 0 开始 @param size 每页数量 @param total 总记录数 */
public record UserPage(List<UserView> items, int page, int size, long total) {
    /** 防止返回集合被调用方修改。 */
    public UserPage {
        items = List.copyOf(items);
    }
}
