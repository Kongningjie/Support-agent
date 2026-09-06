package com.lawrence.supportagent.asynctask;

import java.util.function.BooleanSupplier;

/**
 * 向任务处理器提供当前任务及受租约所有权保护的续租能力。
 *
 * @param task 已进入 RUNNING 状态的任务快照
 * @param leaseRenewer 把当前任务租约延长五分钟的回调
 */
public record AsyncTaskExecutionContext(AsyncTask task, BooleanSupplier leaseRenewer) {
    /** 执行一次续租；返回 false 表示当前 Worker 已失去任务所有权。 */
    public boolean renewLease() {
        return leaseRenewer.getAsBoolean();
    }
}
