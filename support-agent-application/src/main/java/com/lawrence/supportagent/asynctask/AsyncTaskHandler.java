package com.lawrence.supportagent.asynctask;

/** 后续阶段为一种任务类型注册的生产处理器扩展点。 */
public interface AsyncTaskHandler {
    /** 返回此处理器唯一支持的任务类型。 */
    AsyncTaskType taskType();

    /** 判断此处理器是否支持任务的关联聚合类型。 */
    default boolean supports(AsyncTask task) {
        return task != null && task.taskType() == taskType();
    }

    /**
     * 在抢占事务提交后执行耗时业务；实现必须保证自身操作可重试。
     *
     * @param context 包含任务快照和按需续租能力的执行上下文
     */
    AsyncTaskBusinessMutation execute(AsyncTaskExecutionContext context);

    /**
     * 返回任务最终死亡时需要与任务状态原子提交的业务变更。
     *
     * @param context 当前任务及续租上下文
     * @param failure 已脱敏和分类的最终失败
     * @return 不调用外部服务的短事务动作
     */
    default AsyncTaskBusinessMutation finalFailureMutation(
            AsyncTaskExecutionContext context, AsyncTaskExecutionException failure) {
        return AsyncTaskBusinessMutation.NONE;
    }
}
