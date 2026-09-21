package com.lawrence.supportagent.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 从类路径 JSONL 加载并严格校验阶段 17 固定安全评测集。 */
public class ClasspathSecurityEvaluationDatasetAdapter implements SecurityEvaluationDatasetPort {
    private static final String RESOURCE = "/evaluation/llm-security-cases.jsonl";
    private final ObjectMapper mapper;

    /** 注入统一 JSON 编解码器。 */
    public ClasspathSecurityEvaluationDatasetAdapter(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "JSON 编解码器不能为空");
    }

    /** {@inheritDoc} */
    @Override
    public List<SecurityEvaluationCase> load() {
        List<SecurityEvaluationCase> cases = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (String line : lines()) {
            try {
                JsonNode node = mapper.readTree(line);
                SecurityEvaluationCase value = new SecurityEvaluationCase(
                        required(node, "caseId"),
                        SecurityEvaluationCategory.valueOf(required(node, "category")),
                        SecurityEvaluationSource.valueOf(required(node, "source")),
                        required(node, "input"),
                        SecurityEvaluationAction.valueOf(required(node, "expectedAction")),
                        strings(node.path("requiredSignals")),
                        strings(node.path("forbiddenSignals")), required(node, "notes"));
                if (!ids.add(value.caseId())) {
                    throw new IllegalArgumentException("安全评测用例 ID 重复");
                }
                cases.add(value);
            } catch (RuntimeException exception) {
                throw new IllegalStateException("固定安全评测集存在非法 JSONL 行", exception);
            }
        }
        validate(cases);
        return List.copyOf(cases);
    }

    /** 校验冻结总数、类别分布、来源与动作契约。 */
    private void validate(List<SecurityEvaluationCase> cases) {
        Map<SecurityEvaluationCategory, Long> expected = Map.of(
                SecurityEvaluationCategory.DIRECT_INJECTION, 20L,
                SecurityEvaluationCategory.INDIRECT_INJECTION, 20L,
                SecurityEvaluationCategory.OUTPUT_LEAK, 15L,
                SecurityEvaluationCategory.NORMAL_HARD, 25L);
        Map<SecurityEvaluationCategory, Long> actual = new EnumMap<>(SecurityEvaluationCategory.class);
        cases.forEach(value -> actual.merge(value.category(), 1L, Long::sum));
        if (cases.size() != 80 || !actual.equals(expected)) {
            throw new IllegalStateException("安全评测集必须符合 20/20/15/25 的冻结分布");
        }
        for (SecurityEvaluationCase value : cases) {
            boolean output = value.source() == SecurityEvaluationSource.MODEL_OUTPUT;
            boolean outputAction = value.expectedAction() == SecurityEvaluationAction.PASS
                    || value.expectedAction() == SecurityEvaluationAction.REGENERATE
                    || value.expectedAction() == SecurityEvaluationAction.REJECT;
            if (output != outputAction) {
                throw new IllegalStateException("安全评测来源与动作不一致：" + value.caseId());
            }
        }
    }

    /** 读取 UTF-8 资源并拆分全部非空 JSONL 行。 */
    private List<String> lines() {
        try (var stream = getClass().getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("安全评测资源不存在");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(value -> !value.isBlank()).toList();
        } catch (IOException exception) {
            throw new IllegalStateException("安全评测资源读取失败", exception);
        }
    }

    /** 读取必填非空字符串。 */
    private String required(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isString() || value.stringValue().isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.stringValue();
    }

    /** 读取仅包含非空字符串的数组。 */
    private List<String> strings(JsonNode node) {
        if (!node.isArray()) throw new IllegalArgumentException("安全评测列表字段必须是数组");
        List<String> values = new ArrayList<>();
        node.forEach(value -> {
            if (!value.isString() || value.stringValue().isBlank()) {
                throw new IllegalArgumentException("安全评测列表元素不能为空");
            }
            values.add(value.stringValue());
        });
        return List.copyOf(values);
    }
}
