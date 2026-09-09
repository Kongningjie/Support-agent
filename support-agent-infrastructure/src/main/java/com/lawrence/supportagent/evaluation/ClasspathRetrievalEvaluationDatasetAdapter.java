package com.lawrence.supportagent.evaluation;

import com.lawrence.supportagent.retrieval.RetrievalStatus;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 从类路径 UTF-8 JSONL 加载并严格校验固定评测集。 */
public class ClasspathRetrievalEvaluationDatasetAdapter
        implements RetrievalEvaluationDatasetPort {
    private static final String RESOURCE = "/evaluation/retrieval-cases.jsonl";
    private final ObjectMapper mapper;

    /** 注入 JSON 编解码器。 */
    public ClasspathRetrievalEvaluationDatasetAdapter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public List<RetrievalEvaluationCase> load() {
        InputStream stream = getClass().getResourceAsStream(RESOURCE);
        if (stream == null) throw new IllegalStateException("固定检索评测集不存在");
        List<RetrievalEvaluationCase> values = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) values.add(parse(line, ids));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("固定检索评测集读取失败", exception);
        }
        validateDistribution(values);
        return List.copyOf(values);
    }

    /** 解析单行并校验六个冻结字段和唯一用例 ID。 */
    private RetrievalEvaluationCase parse(String line, Set<String> ids) {
        try {
            JsonNode node = mapper.readTree(line);
            if (!node.isObject() || node.size() != 6) throw new IllegalArgumentException("评测字段数量错误");
            String caseId = required(node, "caseId");
            String query = required(node, "query");
            String description = required(node, "description");
            if (!ids.add(caseId)) throw new IllegalArgumentException("评测用例 ID 重复");
            List<String> relevant = strings(node.path("relevantSourceIds"));
            List<String> exact = strings(node.path("requiredExactTerms"));
            RetrievalStatus status = RetrievalStatus.valueOf(required(node, "expectedStatus"));
            return new RetrievalEvaluationCase(caseId, query, relevant, status, exact, description);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("固定检索评测集存在非法 JSONL 行", exception);
        }
    }

    /** 读取必填非空文本字段。 */
    private String required(JsonNode node, String name) {
        String value = node.path(name).asText();
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value;
    }

    /** 读取只包含非空字符串的数组字段。 */
    private List<String> strings(JsonNode node) {
        if (!node.isArray()) throw new IllegalArgumentException("评测列表字段必须是数组");
        List<String> values = new ArrayList<>();
        node.forEach(value -> values.add(requiredText(value)));
        return List.copyOf(values);
    }

    /** 校验数组元素为非空文本。 */
    private String requiredText(JsonNode node) {
        String value = node.asText();
        if (!node.isString() || value.isBlank()) throw new IllegalArgumentException("评测列表元素不能为空");
        return value;
    }

    /** 校验一期固定的 20、10、10、5、5 用例分布。 */
    private void validateDistribution(List<RetrievalEvaluationCase> values) {
        if (values.size() != 50
                || count(values, "KNOWN-") != 20 || count(values, "EXACT-") != 10
                || count(values, "PARAPHRASE-") != 10 || count(values, "NOHIT-") != 5
                || count(values, "CONFLICT-") != 5) {
            throw new IllegalStateException("固定评测集数量或分类分布不符合 20/10/10/5/5");
        }
    }

    /** 统计指定稳定 ID 前缀的用例数量。 */
    private long count(List<RetrievalEvaluationCase> values, String prefix) {
        return values.stream().filter(value -> value.caseId().startsWith(prefix)).count();
    }
}
