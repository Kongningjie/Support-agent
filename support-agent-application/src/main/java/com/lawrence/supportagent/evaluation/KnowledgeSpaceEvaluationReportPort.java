package com.lawrence.supportagent.evaluation;

/** 将通过完整计算的空间隔离评测报告写入构建产物目录。 */
public interface KnowledgeSpaceEvaluationReportPort {
    /** 写出一份不可覆盖的动态报告。 */
    void write(KnowledgeSpaceEvaluationReport report);
}
