package com.lawrence.supportagent.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 隔离 Judge 执行占用、幂等和仅含结构化结果的持久化。 */
public interface JudgeRunRepository {
    /** 查询操作者的幂等请求。 */
    Optional<Run> find(UUID actorId, UUID key);
    /** 短事务占用样本；相同样本活动租约或重复 Key 返回冲突。 */
    void claim(Run run);
    /** 仅允许尚有活动租约的运行完成，失败不能覆盖另一运行。 */
    void complete(UUID runId, List<Attempt> attempts, Instant now);
    /** 查询指定样本最近二十次运行。 */
    List<Run> list(UUID sampleId);
    /** 读取最多1001条人工已决运行；多出一条用于标记聚合截断。 */
    List<Run> calibrationRuns();
    /** 单次复测结果；failure 为空才有 result，elapsedMillis 为调用耗时。 */
    record Attempt(int ordinal, JudgeModelPort.Result result, String failure, long elapsedMillis) { }
    /** durable 状态为 RUNNING、COMPLETED 或 FAILED；租约到期不会自动再调用。 */
    record Run(UUID id, UUID sampleId, UUID actorId, UUID key, String model, String promptVersion,
               String status, int estimatedInputTokens, Instant startedAt, Instant expiresAt,
               List<Attempt> attempts) { }
}
