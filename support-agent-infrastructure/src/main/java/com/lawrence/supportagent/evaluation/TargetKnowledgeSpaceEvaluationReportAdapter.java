package com.lawrence.supportagent.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

/** 将空间隔离评测报告写入 target，避免动态结果进入 Git。 */
public class TargetKnowledgeSpaceEvaluationReportAdapter
        implements KnowledgeSpaceEvaluationReportPort {
    private final ObjectMapper mapper;
    private final Path directory;

    /** 使用冻结的构建产物子目录创建报告适配器。 */
    public TargetKnowledgeSpaceEvaluationReportAdapter(ObjectMapper mapper) {
        this(mapper, Path.of("target", "knowledge-space-evaluation"));
    }

    /** 注入 JSON 编解码器和可测试报告目录。 */
    public TargetKnowledgeSpaceEvaluationReportAdapter(ObjectMapper mapper, Path directory) {
        this.mapper = Objects.requireNonNull(mapper, "JSON 编解码器不能为空");
        this.directory = Objects.requireNonNull(directory, "报告目录不能为空");
    }

    /** {@inheritDoc} */
    @Override
    public void write(KnowledgeSpaceEvaluationReport report) {
        if (report == null) throw new IllegalArgumentException("空间隔离评测报告不能为空");
        try {
            Files.createDirectories(directory);
            Path target = directory.resolve(report.runId() + ".json");
            Files.writeString(target, mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(report), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException exception) {
            throw new IllegalStateException("空间隔离评测报告写入失败", exception);
        }
    }
}
