package com.lawrence.supportagent.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import tools.jackson.databind.ObjectMapper;

/** 将阶段七参数实验报告写入 target，避免动态结果进入 Git。 */
public class TargetRetrievalExperimentReportAdapter implements RetrievalExperimentReportPort {
    private final ObjectMapper mapper;
    private final Path directory;

    /** 使用当前模块的阶段七构建产物目录保存报告。 */
    public TargetRetrievalExperimentReportAdapter(ObjectMapper mapper) {
        this(mapper, Path.of("target", "stage-7-optimization"));
    }

    /** 注入 JSON 编解码器和可测试的报告目录。 */
    public TargetRetrievalExperimentReportAdapter(ObjectMapper mapper, Path directory) {
        this.mapper = java.util.Objects.requireNonNull(mapper, "JSON 编解码器不能为空");
        this.directory = java.util.Objects.requireNonNull(directory, "报告目录不能为空");
    }

    /** {@inheritDoc} */
    @Override
    public void write(RetrievalExperimentReport report) {
        if (report == null) {
            throw new IllegalArgumentException("检索实验报告不能为空");
        }
        try {
            Files.createDirectories(directory);
            Path output = directory.resolve("experiment-" + report.reportId() + ".json");
            Files.writeString(output, mapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(report), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException exception) {
            throw new IllegalStateException("检索实验报告写入失败", exception);
        }
    }
}
