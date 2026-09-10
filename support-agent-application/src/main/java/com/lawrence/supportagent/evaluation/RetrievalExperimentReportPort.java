package com.lawrence.supportagent.evaluation;

/** 隔离阶段七不可变参数实验报告的具体存储位置。 */
public interface RetrievalExperimentReportPort {
    /** 把一份完整报告写入不可覆盖的构建产物目录。 */
    void write(RetrievalExperimentReport report);
}
