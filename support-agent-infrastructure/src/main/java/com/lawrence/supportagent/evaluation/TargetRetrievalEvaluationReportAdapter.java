package com.lawrence.supportagent.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.ObjectMapper;

/** 将完成的评测快照写入 Maven target 目录，避免污染源码和配置。 */
public class TargetRetrievalEvaluationReportAdapter implements RetrievalEvaluationReportPort {
    private final ObjectMapper mapper;
    private final Path directory;

    /** 使用当前模块构建目录创建报告适配器。 */
    public TargetRetrievalEvaluationReportAdapter(ObjectMapper mapper) {
        this(mapper, Path.of("target", "retrieval-evaluation"));
    }

    /** 注入 JSON 编解码器和可测试报告目录。 */
    public TargetRetrievalEvaluationReportAdapter(ObjectMapper mapper, Path directory) {
        this.mapper = mapper;
        this.directory = directory;
    }

    /** {@inheritDoc} */
    @Override public void write(RetrievalEvaluationRun run) {
        if (run == null || run.status() != RetrievalEvaluationRun.Status.SUCCEEDED) {
            throw new IllegalArgumentException("只能写出已成功的检索评测");
        }
        try {
            Files.createDirectories(directory);
            Path report = directory.resolve(run.evaluationRunId() + ".json");
            Files.writeString(report, mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(run), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("检索评测报告写入失败", exception);
        }
    }
}
