package com.lawrence.supportagent.asynctask;

/** 表示关联业务对象已失效，当前异步任务应安全取消而非失败。 */
public class AsyncTaskCancelledException extends RuntimeException {
    private final AsyncTaskBusinessMutation businessMutation;

    /** 使用安全原因和可选短事务业务动作创建取消信号。 */
    public AsyncTaskCancelledException(String safeMessage,
                                       AsyncTaskBusinessMutation businessMutation) {
        super(safeMessage);
        this.businessMutation = businessMutation == null
                ? AsyncTaskBusinessMutation.NONE : businessMutation;
    }

    /** 返回与任务取消状态原子提交的业务动作。 */
    public AsyncTaskBusinessMutation businessMutation() {
        return businessMutation;
    }
}
