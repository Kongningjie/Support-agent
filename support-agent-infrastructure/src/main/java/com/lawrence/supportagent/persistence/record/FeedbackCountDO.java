package com.lawrence.supportagent.persistence.record;

/** MyBatis 聚合行，键只允许稳定评分与原因枚举。 */
public class FeedbackCountDO {
    /** 聚合分类值。 */
    public String category;
    /** 当前分类记录数。 */
    public long count;
}
