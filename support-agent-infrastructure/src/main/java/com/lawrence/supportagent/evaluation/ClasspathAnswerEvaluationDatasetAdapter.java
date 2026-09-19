package com.lawrence.supportagent.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 从类路径 JSONL 加载并严格校验阶段 8 固定回答评测集。 */
public class ClasspathAnswerEvaluationDatasetAdapter implements AnswerEvaluationDatasetPort {
    private static final String CASE_RESOURCE = "/evaluation/answer-evaluation-cases.jsonl";
    private static final String CORPUS_RESOURCE = "/evaluation/retrieval-corpus.jsonl";
    private static final String MANIFEST_RESOURCE = "/evaluation/answer-evaluation-manifest.json";
    private final ObjectMapper mapper;

    /** 注入统一 JSON 编解码器。 */
    public ClasspathAnswerEvaluationDatasetAdapter(ObjectMapper mapper) { this.mapper = mapper; }

    /** {@inheritDoc} */
    @Override
    public AnswerEvaluationDatasetSnapshot load() {
        byte[] caseBytes = read(CASE_RESOURCE);
        byte[] corpusBytes = read(CORPUS_RESOURCE);
        Map<String, AnswerEvaluationEvidence> catalog = catalog(corpusBytes);
        List<AnswerEvaluationCase> cases = cases(caseBytes, catalog);
        validate(cases);
        String actualHash = sha256(caseBytes, corpusBytes);
        JsonNode manifest = manifest();
        String expectedHash = required(manifest, "contentSha256");
        if (!actualHash.equals(expectedHash)) {
            throw new IllegalStateException("回答评测数据内容与冻结清单哈希不一致");
        }
        return new AnswerEvaluationDatasetSnapshot(required(manifest, "version"), actualHash,
                List.copyOf(cases));
    }

    /** 解析固定检索语料并建立稳定来源目录。 */
    private Map<String, AnswerEvaluationEvidence> catalog(byte[] bytes) {
        Map<String, AnswerEvaluationEvidence> values = new HashMap<>();
        for (String line : lines(bytes)) {
            try {
                JsonNode node = mapper.readTree(line);
                String[] source = required(node, "sourceId").split(":", 2);
                AnswerEvaluationEvidence evidence = new AnswerEvaluationEvidence(
                        required(node, "sourceKey"), source[0], Long.parseLong(source[1]),
                        required(node, "title"), required(node, "content"));
                if (values.put(evidence.sourceKey(), evidence) != null) {
                    throw new IllegalArgumentException("回答评测语料来源标识重复");
                }
            } catch (RuntimeException exception) {
                throw new IllegalStateException("回答评测引用语料存在非法 JSONL 行", exception);
            }
        }
        return Map.copyOf(values);
    }

    /** 解析全部非空回答用例并解析其来源引用。 */
    private List<AnswerEvaluationCase> cases(byte[] bytes,
                                             Map<String, AnswerEvaluationEvidence> catalog) {
        List<AnswerEvaluationCase> values = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (String line : lines(bytes)) {
            try {
                JsonNode node = mapper.readTree(line);
                String caseId = required(node, "caseId");
                if (!ids.add(caseId)) throw new IllegalArgumentException("回答评测用例 ID 重复");
                List<AnswerEvaluationEvidence> evidence = strings(node.path("evidenceSourceKeys"))
                        .stream().map(key -> requiredEvidence(catalog, key)).toList();
                values.add(new AnswerEvaluationCase(caseId,
                        AnswerEvaluationCategory.valueOf(required(node, "category")),
                        required(node, "input"), evidence, strings(node.path("allowedCitations")),
                        strings(node.path("requiredFacts")), strings(node.path("forbiddenFacts")),
                        strings(node.path("exactValues")), requiredBoolean(node, "expectedRefusal"),
                        AnswerEvaluationSchemaType.valueOf(required(node, "schemaType"))));
            } catch (RuntimeException exception) {
                throw new IllegalStateException("固定回答评测集存在非法 JSONL 行", exception);
            }
        }
        return values;
    }

    /** 对数量、固定类别分布和输出契约一致性执行治理门禁。 */
    private void validate(List<AnswerEvaluationCase> cases) {
        Map<AnswerEvaluationCategory, Long> expected = Map.of(
                AnswerEvaluationCategory.GROUNDED, 10L,
                AnswerEvaluationCategory.EXACT, 5L,
                AnswerEvaluationCategory.NO_HIT, 5L,
                AnswerEvaluationCategory.CONFLICT, 5L,
                AnswerEvaluationCategory.CASE_GENERATION, 5L);
        if (cases.size() != 30) throw new IllegalStateException("回答评测集必须恰好包含 30 条用例");
        Map<AnswerEvaluationCategory, Long> actual = new EnumMap<>(AnswerEvaluationCategory.class);
        for (AnswerEvaluationCase value : cases) {
            actual.merge(value.category(), 1L, Long::sum);
            boolean noHit = value.category() == AnswerEvaluationCategory.NO_HIT;
            boolean structured = value.category() == AnswerEvaluationCategory.CASE_GENERATION;
            boolean evidenceMustBeEmpty = noHit || structured;
            if (value.expectedRefusal() != noHit
                    || noHit != (value.schemaType() == AnswerEvaluationSchemaType.NO_KNOWLEDGE)
                    || structured != (value.schemaType() == AnswerEvaluationSchemaType.RESOLVED_CASE)
                    || evidenceMustBeEmpty != value.evidence().isEmpty()) {
                throw new IllegalStateException("回答评测用例契约不一致：" + value.caseId());
            }
            if (value.requiredFacts().isEmpty()) {
                throw new IllegalStateException("回答评测用例缺少必需事实：" + value.caseId());
            }
            List<String> expectedCitations = java.util.stream.IntStream
                    .rangeClosed(1, value.evidence().size()).mapToObj(index -> "S" + index).toList();
            if (!value.allowedCitations().equals(expectedCitations)) {
                throw new IllegalStateException("回答评测允许引用与证据顺序不一致：" + value.caseId());
            }
        }
        if (!actual.equals(expected)) throw new IllegalStateException("回答评测类别数量不符合冻结分布");
    }

    /** 返回指定稳定来源，不允许用例引用未知语料。 */
    private AnswerEvaluationEvidence requiredEvidence(
            Map<String, AnswerEvaluationEvidence> catalog, String key) {
        AnswerEvaluationEvidence value = catalog.get(key);
        if (value == null) throw new IllegalArgumentException("回答评测引用未知 sourceKey");
        return value;
    }

    /** 读取冻结清单。 */
    private JsonNode manifest() {
        try {
            return mapper.readTree(read(MANIFEST_RESOURCE));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("回答评测冻结清单无效", exception);
        }
    }

    /** 读取指定类路径资源的全部原始字节。 */
    private byte[] read(String resource) {
        try (var stream = getClass().getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("回答评测资源不存在：" + resource);
            return stream.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("回答评测资源读取失败：" + resource, exception);
        }
    }

    /** 将 UTF-8 内容拆分为非空 JSONL 行。 */
    private List<String> lines(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).lines()
                .filter(value -> !value.isBlank()).toList();
    }

    /** 读取必填非空字符串。 */
    private String required(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isString() || value.stringValue().isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.stringValue();
    }

    /** 读取必填布尔值。 */
    private boolean requiredBoolean(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isBoolean()) throw new IllegalArgumentException(name + " 必须是布尔值");
        return value.asBoolean();
    }

    /** 读取仅包含非空字符串的数组。 */
    private List<String> strings(JsonNode node) {
        if (!node.isArray()) throw new IllegalArgumentException("回答评测列表字段必须是数组");
        List<String> values = new ArrayList<>();
        node.forEach(value -> {
            if (!value.isString() || value.stringValue().isBlank()) {
                throw new IllegalArgumentException("回答评测列表元素不能为空");
            }
            values.add(value.stringValue());
        });
        return List.copyOf(values);
    }

    /** 对用例原始字节、分隔符和引用语料生成稳定 SHA-256。 */
    private String sha256(byte[] cases, byte[] corpus) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(cases);
            digest.update((byte) '\n');
            digest.update(corpus);
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }
}
