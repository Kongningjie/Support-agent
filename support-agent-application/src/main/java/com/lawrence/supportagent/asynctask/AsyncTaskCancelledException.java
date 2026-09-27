package com.lawrence.supportagent.asynctask;

/** 表示关联业务对象已失效，当前异步任务应安全取消而非失败。 */
public class AsyncTaskCancelledException extends RuntimeException {
    private final String errorCode;
    private final AsyncTaskBusinessMutation businessMutation;

    /** 使用安全原因和可选短事务业务动作创建取消信号。 */
    public AsyncTaskCancelledException(String safeMessage,
                                       AsyncTaskBusinessMutation businessMutation) {
        this("ASYNC_TASK_CANCELLED", safeMessage, businessMutation);
    }

    /** 使用稳定原因码、安全摘要和可选短事务业务动作创建取消信号。 */
    public AsyncTaskCancelledException(String errorCode, String safeMessage,
                                       AsyncTaskBusinessMutation businessMutation) {
        super(safeMessage);
        this.errorCode = errorCode == null || errorCode.isBlank()
                ? "ASYNC_TASK_CANCELLED" : errorCode;
        this.businessMutation = businessMutation == null
                ? AsyncTaskBusinessMutation.NONE : businessMutation;
    }

    /** 返回可以持久化和观测的低基数稳定取消原因码。 */
    public String errorCode() {
        return errorCode;
    }

    /** 返回与任务取消状态原子提交的业务动作。 */
    public AsyncTaskBusinessMutation businessMutation() {
        return businessMutation;
    }
}
