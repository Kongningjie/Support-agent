package com.lawrence.supportagent.asynctask;

/** 后续阶段为一种任务类型注册的生产处理器扩展点。 */
public interface AsyncTaskHandler {
    /** 返回此处理器唯一支持的任务类型。 */
    AsyncTaskType taskType();

    /**
     * 在抢占事务提交后执行耗时业务；实现必须保证自身操作可重试。
     *
     * @param context 包含任务快照和按需续租能力的执行上下文
     */
    void execute(AsyncTaskExecutionContext context);
}
