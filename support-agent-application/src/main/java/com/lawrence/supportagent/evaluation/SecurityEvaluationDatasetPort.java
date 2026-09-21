package com.lawrence.supportagent.evaluation;

import java.util.List;

/** 加载版本受控的阶段 17 固定中文安全评测集。 */
public interface SecurityEvaluationDatasetPort {
    /** 返回经过数量、分布和字段校验的全部样本。 */
    List<SecurityEvaluationCase> load();
}
