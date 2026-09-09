package com.lawrence.supportagent.evaluation;

/** 把已完成评测结果写入构建产物目录。 */
public interface RetrievalEvaluationReportPort {
    /** 写出一个不会改变数据集或运行参数的正式报告。 */
    void write(RetrievalEvaluationRun run);
}
