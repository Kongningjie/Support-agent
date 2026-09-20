package com.lawrence.supportagent.memory.port;

import com.lawrence.supportagent.memory.CandidateInsertOutcome;

/** 接收长期记忆候选运行治理所需的低基数指标，禁止携带用户或正文。 */
public interface MemoryCandidateTelemetryPort {
    /** 记录一次启用检查前的候选请求。 */
    void recordRequest();

    /** 记录一次已完成模型响应的业务结果。 */
    void recordOutcome(CandidateOutcome outcome);

    /** 记录一次无等待并发拒绝。 */
    void recordRejection(CandidateRejectionReason reason);

    /** 记录一次被隔离的候选生成失败。 */
    void recordFailure(CandidateFailureReason reason);

    /** 记录单条候选的数据库写入分类。 */
    void recordInsertion(CandidateInsertOutcome outcome);

    /** 增加当前持有全局许可的候选执行数。 */
    void executionStarted();

    /** 减少当前持有全局许可的候选执行数。 */
    void executionFinished();

    /** 记录从取得许可到释放许可的总耗时。 */
    void recordDuration(long durationMs);

    /** 记录一次后台清理触发、删除数量、耗时和成败。 */
    void recordCleanup(int deletedCount, long durationMs, boolean succeeded);

    /** 返回没有任何外部副作用的默认实现。 */
    static MemoryCandidateTelemetryPort noOp() {
        return new MemoryCandidateTelemetryPort() {
            /** {@inheritDoc} */
            @Override public void recordRequest() { }
            /** {@inheritDoc} */
            @Override public void recordOutcome(CandidateOutcome outcome) { }
            /** {@inheritDoc} */
            @Override public void recordRejection(CandidateRejectionReason reason) { }
            /** {@inheritDoc} */
            @Override public void recordFailure(CandidateFailureReason reason) { }
            /** {@inheritDoc} */
            @Override public void recordInsertion(CandidateInsertOutcome outcome) { }
            /** {@inheritDoc} */
            @Override public void executionStarted() { }
            /** {@inheritDoc} */
            @Override public void executionFinished() { }
            /** {@inheritDoc} */
            @Override public void recordDuration(long durationMs) { }
            /** {@inheritDoc} */
            @Override public void recordCleanup(int deletedCount, long durationMs,
                                                boolean succeeded) { }
        };
    }

    /** 模型正常完成后的固定业务结果。 */
    enum CandidateOutcome {
        /** 模型返回至少一个通过结构校验的候选。 */ SUCCESS,
        /** 模型明确返回空候选集合。 */ EMPTY
    }

    /** 并发许可不可立即取得时的固定原因。 */
    enum CandidateRejectionReason {
        /** 单实例全局并发已满。 */ GLOBAL_LIMIT,
        /** 当前用户已有候选模型请求在执行。 */ USER_LIMIT
    }

    /** 候选生成失败的固定低基数原因。 */
    enum CandidateFailureReason {
        /** 模型超时、不可用或其他安全包装调用异常。 */ MODEL,
        /** 模型响应或候选字段不满足冻结结构。 */ SCHEMA,
        /** 候选包含凭据或个人敏感信息。 */ SENSITIVE_CONTENT,
        /** 候选设置读取或持久化失败。 */ DATABASE,
        /** 虚拟线程执行器拒绝了任务。 */ EXECUTOR
    }
}
