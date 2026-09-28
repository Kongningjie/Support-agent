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

/** 从类路径 JSONL 加载并严格校验阶段 20 固定知识空间隔离评测集。 */
public class ClasspathKnowledgeSpaceEvaluationDatasetAdapter
        implements KnowledgeSpaceEvaluationDatasetPort {
    private static final String RESOURCE = "/evaluation/knowledge-space-cases.jsonl";
    private static final Set<String> RESULTS = Set.of("ALLOW", "DENY", "NOT_FOUND", "NO_HIT");
    private final ObjectMapper mapper;

    /** 注入统一 JSON 编解码器。 */
    public ClasspathKnowledgeSpaceEvaluationDatasetAdapter(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "JSON 编解码器不能为空");
    }

    /** {@inheritDoc} */
    @Override
    public List<KnowledgeSpaceEvaluationCase> load() {
        List<KnowledgeSpaceEvaluationCase> cases = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (String line : lines()) {
            try {
                JsonNode node = mapper.readTree(line);
                KnowledgeSpaceEvaluationCase value = new KnowledgeSpaceEvaluationCase(
                        required(node, "caseId"),
                        KnowledgeSpaceEvaluationCategory.valueOf(required(node, "category")),
                        required(node, "spaceCode"), required(node, "userRole"),
                        required(node, "input"), strings(node.path("expectedSources")),
                        strings(node.path("forbiddenSources")),
                        strings(node.path("forbiddenDisclosures")),
                        strings(node.path("requiredExactTerms")),
                        required(node, "expectedResult"));
                if (!ids.add(value.caseId())) {
                    throw new IllegalArgumentException("空间隔离评测用例 ID 重复");
                }
                cases.add(value);
            } catch (RuntimeException exception) {
                throw new IllegalStateException("固定空间隔离评测集存在非法 JSONL 行", exception);
            }
        }
        validate(cases);
        return List.copyOf(cases);
    }

    /** 校验不少于六十条以及五类场景的冻结最小分布。 */
    private void validate(List<KnowledgeSpaceEvaluationCase> cases) {
        Map<KnowledgeSpaceEvaluationCategory, Long> minimums = Map.of(
                KnowledgeSpaceEvaluationCategory.GLOBAL_AND_ACTIVE, 15L,
                KnowledgeSpaceEvaluationCategory.RESTRICTED_CONFLICT, 15L,
                KnowledgeSpaceEvaluationCategory.PERMISSION_CHANGE, 10L,
                KnowledgeSpaceEvaluationCategory.RESOURCE_INHERITANCE, 10L,
                KnowledgeSpaceEvaluationCategory.ENUMERATION_AND_NORMAL, 10L);
        Map<KnowledgeSpaceEvaluationCategory, Long> actual =
                new EnumMap<>(KnowledgeSpaceEvaluationCategory.class);
        cases.forEach(value -> actual.merge(value.category(), 1L, Long::sum));
        if (cases.size() < 60 || minimums.entrySet().stream().anyMatch(
                entry -> actual.getOrDefault(entry.getKey(), 0L) < entry.getValue())) {
            throw new IllegalStateException("空间隔离评测集不满足 15/15/10/10/10 最小分布");
        }
        for (KnowledgeSpaceEvaluationCase value : cases) {
            if (!value.caseId().matches("KS-[A-Z]+-[0-9]{3}")
                    || !value.spaceCode().matches("(GLOBAL|TEST_[A-Z]+)")
                    || !Set.of("NONE", "READER", "EDITOR", "MANAGER", "ADMIN")
                    .contains(value.userRole())
                    || !RESULTS.contains(value.expectedResult())) {
                throw new IllegalStateException("空间隔离评测字段不符合冻结枚举：" + value.caseId());
            }
        }
    }

    /** 读取 UTF-8 资源并拆分全部非空 JSONL 行。 */
    private List<String> lines() {
        try (var stream = getClass().getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("空间隔离评测资源不存在");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(value -> !value.isBlank()).toList();
        } catch (IOException exception) {
            throw new IllegalStateException("空间隔离评测资源读取失败", exception);
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

    /** 读取仅包含非空字符串的数组，允许空数组表达无适用值。 */
    private List<String> strings(JsonNode node) {
        if (!node.isArray()) throw new IllegalArgumentException("空间隔离评测列表字段必须是数组");
        List<String> values = new ArrayList<>();
        node.forEach(value -> {
            if (!value.isString() || value.stringValue().isBlank()) {
                throw new IllegalArgumentException("空间隔离评测列表元素不能为空");
            }
            values.add(value.stringValue());
        });
        return List.copyOf(values);
    }
}
