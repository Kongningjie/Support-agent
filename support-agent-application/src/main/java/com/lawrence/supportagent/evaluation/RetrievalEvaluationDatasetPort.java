package com.lawrence.supportagent.evaluation;

import java.util.List;

/** 读取仓库内固定评测集的端口。 */
public interface RetrievalEvaluationDatasetPort {
    /** 加载全部固定评测用例。 */
    List<RetrievalEvaluationCase> load();
}
