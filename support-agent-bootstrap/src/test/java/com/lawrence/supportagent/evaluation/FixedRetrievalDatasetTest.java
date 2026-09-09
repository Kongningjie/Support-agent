package com.lawrence.supportagent.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 验证仓库内自编中文评测集数量、分布和字段约束。 */
class FixedRetrievalDatasetTest {
    /** 验证加载器严格接受 20、10、10、5、5 共五十条用例。 */
    @Test
    void shouldLoadFrozenFiftyCases() {
        List<RetrievalEvaluationCase> cases =
                new ClasspathRetrievalEvaluationDatasetAdapter(new ObjectMapper()).load();

        assertEquals(50, cases.size());
        assertEquals(20, count(cases, "KNOWN-"));
        assertEquals(10, count(cases, "EXACT-"));
        assertEquals(10, count(cases, "PARAPHRASE-"));
        assertEquals(5, count(cases, "NOHIT-"));
        assertEquals(5, count(cases, "CONFLICT-"));
        assertTrue(cases.stream().allMatch(value -> !value.query().isBlank()
                && !value.description().isBlank()));
        Set<String> corpusIds = loadCorpusIds();
        assertEquals(15, corpusIds.size());
        assertTrue(cases.stream().flatMap(value -> value.relevantSourceIds().stream())
                .allMatch(corpusIds::contains));
    }

    /** 统计具有指定稳定前缀的用例。 */
    private long count(List<RetrievalEvaluationCase> cases, String prefix) {
        return cases.stream().filter(value -> value.caseId().startsWith(prefix)).count();
    }

    /** 读取固定语料并返回所有稳定来源 ID。 */
    private Set<String> loadCorpusIds() {
        Set<String> values = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                java.util.Objects.requireNonNull(getClass().getResourceAsStream(
                        "/evaluation/retrieval-corpus.jsonl")), StandardCharsets.UTF_8))) {
            String line;
            ObjectMapper mapper = new ObjectMapper();
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) values.add(mapper.readTree(line).path("sourceId").asText());
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("固定检索语料无法读取", exception);
        }
        return Set.copyOf(values);
    }
}
