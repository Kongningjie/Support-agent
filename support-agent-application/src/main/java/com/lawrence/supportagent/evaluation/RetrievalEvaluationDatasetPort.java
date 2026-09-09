package com.lawrence.supportagent.evaluation;

/** 读取仓库内固定评测集的端口。 */
public interface RetrievalEvaluationDatasetPort {
    /** 加载指定用途且完成哈希校验的评测数据快照。 */
    RetrievalEvaluationDatasetSnapshot load(EvaluationDatasetKind kind);

    /** 为原有调用加载锁定回归集。 */
    default java.util.List<RetrievalEvaluationCase> load() {
        return load(EvaluationDatasetKind.LOCKED_REGRESSION).cases();
    }
}
