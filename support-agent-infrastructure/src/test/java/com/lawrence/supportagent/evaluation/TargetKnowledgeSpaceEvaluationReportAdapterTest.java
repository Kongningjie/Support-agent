package com.lawrence.supportagent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** 验证空间隔离动态报告只写入指定构建目录。 */
class TargetKnowledgeSpaceEvaluationReportAdapterTest {
    /** 报告文件名必须使用运行 UUID 且内容可审计。 */
    @Test
    void shouldWriteReportIntoTargetDirectory(@TempDir Path directory) throws Exception {
        UUID runId = UUID.fromString("90000000-0000-0000-0000-000000000001");
        KnowledgeSpaceEvaluationReport report = new KnowledgeSpaceEvaluationReport(
                runId, Instant.parse("2026-09-27T00:00:00Z"), 60,
                0, 0, 0, 0, 1D, 1D, 1D, 1D, 1D, 0, List.of(), true);
        new TargetKnowledgeSpaceEvaluationReportAdapter(
                JsonMapper.builder().build(), directory).write(report);

        Path file = directory.resolve(runId + ".json");
        assertThat(file).exists();
        assertThat(Files.readString(file)).contains("\"passed\" : true")
                .doesNotContain("query", "knowledgeBody", "modelOutput");
    }
}
