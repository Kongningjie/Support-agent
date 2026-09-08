package com.lawrence.supportagent.asynctask;

/** 表示与异步任务终态一起提交的短小 MySQL 业务变更。 */
@FunctionalInterface
public interface AsyncTaskBusinessMutation {
    /** 不执行额外业务变更的共享动作。 */
    AsyncTaskBusinessMutation NONE = () -> { };

    /** 在任务终态事务内执行且不得调用外部服务。 */
    void apply();
}
