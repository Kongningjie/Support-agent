package com.lawrence.supportagent.evaluation;

import com.lawrence.supportagent.retrieval.RetrievalStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 从类路径 UTF-8 JSONL 加载、规范化并严格校验两套固定评测集。 */
public class ClasspathRetrievalEvaluationDatasetAdapter implements RetrievalEvaluationDatasetPort {
    private static final String LOCKED_RESOURCE = "/evaluation/retrieval-cases.jsonl";
    private static final String DEVELOPMENT_RESOURCE = "/evaluation/optimization-development-cases.jsonl";
    private static final String CORPUS_RESOURCE = "/evaluation/retrieval-corpus.jsonl";
    private static final String MANIFEST_RESOURCE = "/evaluation/dataset-manifest.json";
    private final ObjectMapper mapper;

    /** 注入 JSON 编解码器。 */
    public ClasspathRetrievalEvaluationDatasetAdapter(ObjectMapper mapper) { this.mapper = mapper; }

    /** {@inheritDoc} */
    @Override
    public RetrievalEvaluationDatasetSnapshot load(EvaluationDatasetKind kind) {
        if (kind == null) throw new IllegalArgumentException("评测数据集用途不能为空");
        String resource = kind == EvaluationDatasetKind.LOCKED_REGRESSION
                ? LOCKED_RESOURCE : DEVELOPMENT_RESOURCE;
        byte[] caseBytes = read(resource);
        byte[] corpusBytes = read(CORPUS_RESOURCE);
        SourceCatalog catalog = parseCatalog(corpusBytes);
        List<RetrievalEvaluationCase> cases = parseCases(caseBytes, kind, catalog);
        validate(kind, cases, catalog);
        String actualHash = sha256(caseBytes, corpusBytes);
        DatasetManifest manifest = manifest(kind);
        if (!actualHash.equals(manifest.contentSha256)) {
            throw new IllegalStateException("评测数据内容与冻结清单哈希不一致");
        }
        return new RetrievalEvaluationDatasetSnapshot(kind, manifest.version, actualHash,
                List.copyOf(cases), Map.copyOf(catalog.byTypeAndTitle));
    }

    /** 读取指定数据集的冻结版本和内容哈希。 */
    private DatasetManifest manifest(EvaluationDatasetKind kind) {
        try {
            JsonNode node = mapper.readTree(read(MANIFEST_RESOURCE)).path(kind.name());
            return new DatasetManifest(required(node, "version"), required(node, "contentSha256"));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("评测数据冻结清单无效", exception);
        }
    }

    /** 读取必需类路径资源的原始字节。 */
    private byte[] read(String resource) {
        try (var stream = getClass().getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("评测资源不存在：" + resource);
            return stream.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("评测资源读取失败：" + resource, exception);
        }
    }

    /** 解析固定语料中的旧运行 ID、稳定来源键及标题映射。 */
    private SourceCatalog parseCatalog(byte[] bytes) {
        Map<String, String> byLegacyId = new HashMap<>();
        Map<String, String> byTypeAndTitle = new HashMap<>();
        Set<String> sourceKeys = new HashSet<>();
        Set<String> normalizedContents = new HashSet<>();
        for (String line : lines(bytes)) {
            try {
                JsonNode node = mapper.readTree(line);
                String legacyId = required(node, "sourceId");
                String sourceKey = required(node, "sourceKey");
                String title = required(node, "title");
                String content = required(node, "content");
                String sourceType = legacyId.substring(0, legacyId.indexOf(':'));
                if (!sourceKeys.add(sourceKey) || byLegacyId.put(legacyId, sourceKey) != null
                        || byTypeAndTitle.put(sourceType + "\u0000" + title, sourceKey) != null) {
                    throw new IllegalArgumentException("评测语料来源标识重复");
                }
                normalizedContents.add(normalize(content));
            } catch (RuntimeException exception) {
                throw new IllegalStateException("固定检索语料存在非法 JSONL 行", exception);
            }
        }
        return new SourceCatalog(Map.copyOf(byLegacyId), Map.copyOf(byTypeAndTitle),
                Set.copyOf(sourceKeys), Set.copyOf(normalizedContents));
    }

    /** 按数据集 Schema 解析全部非空 JSONL 行。 */
    private List<RetrievalEvaluationCase> parseCases(byte[] bytes, EvaluationDatasetKind kind,
                                                      SourceCatalog catalog) {
        List<RetrievalEvaluationCase> values = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (String line : lines(bytes)) values.add(parseCase(line, kind, catalog, ids));
        return values;
    }

    /** 解析单条用例并把一期自增 ID 标注迁移为稳定 sourceKey。 */
    private RetrievalEvaluationCase parseCase(String line, EvaluationDatasetKind kind,
                                               SourceCatalog catalog, Set<String> ids) {
        try {
            JsonNode node = mapper.readTree(line);
            String caseId = required(node, "caseId");
            if (!ids.add(caseId)) throw new IllegalArgumentException("评测用例 ID 重复");
            String query = required(node, "query");
            RetrievalStatus status = RetrievalStatus.valueOf(required(node, "expectedStatus"));
            List<String> exact = strings(node.path("requiredExactTerms"));
            String description = required(node, "description");
            if (kind == EvaluationDatasetKind.LOCKED_REGRESSION) {
                List<String> keys = strings(node.path("relevantSourceIds")).stream()
                        .map(value -> normalizeSourceKey(value, catalog)).toList();
                return new RetrievalEvaluationCase(caseId, query, keys, status, exact, description);
            }
            List<String> keys = strings(node.path("relevantSourceKeys"));
            return new RetrievalEvaluationCase(caseId, query, keys, status, exact, description,
                    required(node, "category"), grades(node.path("relevanceGrades")),
                    requiredBoolean(node, "shouldBeNoHit"),
                    requiredBoolean(node, "requiresMultiTurnContext"),
                    requiredBoolean(node, "hasKnowledgeConflict"));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("固定检索评测集存在非法 JSONL 行", exception);
        }
    }

    /** 将旧来源 ID 解析为稳定来源键。 */
    private String normalizeSourceKey(String value, SourceCatalog catalog) {
        String key = catalog.byLegacyId.get(value);
        if (key == null) throw new IllegalArgumentException("评测用例引用未知旧来源 ID");
        return key;
    }

    /** 对数据集规模、分布、重复问题和标注一致性执行治理门禁。 */
    private void validate(EvaluationDatasetKind kind, List<RetrievalEvaluationCase> values,
                          SourceCatalog catalog) {
        Map<String, Long> expected = kind == EvaluationDatasetKind.LOCKED_REGRESSION
                ? Map.of("KNOWN", 20L, "EXACT", 10L, "PARAPHRASE", 10L,
                        "NOHIT", 5L, "CONFLICT", 5L)
                : Map.of("DIRECT", 30L, "NOISY", 30L, "EXACT", 30L,
                        "MULTITURN", 20L, "NOHIT", 20L, "CONFLICT", 20L);
        int expectedSize = kind == EvaluationDatasetKind.LOCKED_REGRESSION ? 50 : 150;
        if (values.size() != expectedSize) throw new IllegalStateException("评测集数量不符合冻结分布");
        expected.forEach((category, count) -> {
            long actual = values.stream().filter(value -> category.equals(value.category())).count();
            if (actual != count) throw new IllegalStateException("评测分类数量不符合冻结分布：" + category);
        });
        Set<String> normalizedQueries = new HashSet<>();
        for (RetrievalEvaluationCase value : values) {
            if (!normalizedQueries.add(normalize(value.query()))) {
                throw new IllegalStateException("评测集包含重复问题：" + value.caseId());
            }
            String normalizedQuery = normalize(value.query());
            if (catalog.normalizedContents.stream().anyMatch(normalizedQuery::contains)) {
                throw new IllegalStateException("评测问题泄漏完整语料答案：" + value.caseId());
            }
            if (!catalog.sourceKeys.containsAll(value.relevantSourceKeys())) {
                throw new IllegalStateException("评测用例引用未知 sourceKey：" + value.caseId());
            }
            if (!value.relevanceGrades().keySet().equals(Set.copyOf(value.relevantSourceKeys()))
                    || value.relevanceGrades().values().stream().anyMatch(grade -> grade < 1 || grade > 3)) {
                throw new IllegalStateException("相关等级与来源标注不一致：" + value.caseId());
            }
            boolean noHit = value.expectedStatus() == RetrievalStatus.NO_RELIABLE_KNOWLEDGE;
            if (value.shouldBeNoHit() != noHit || noHit != value.relevantSourceKeys().isEmpty()) {
                throw new IllegalStateException("无命中标注不一致：" + value.caseId());
            }
            if (value.description().length() < 8) {
                throw new IllegalStateException("用例判断依据过短：" + value.caseId());
            }
        }
    }

    /** 解析一至三级相关等级对象。 */
    private Map<String, Integer> grades(JsonNode node) {
        if (!node.isObject()) throw new IllegalArgumentException("相关等级必须是对象");
        Map<String, Integer> values = new LinkedHashMap<>();
        node.properties().forEach(entry -> {
            if (!entry.getValue().isIntegralNumber()) throw new IllegalArgumentException("相关等级必须是整数");
            values.put(entry.getKey(), entry.getValue().asInt());
        });
        return Map.copyOf(values);
    }

    /** 读取必填布尔字段。 */
    private boolean requiredBoolean(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isBoolean()) throw new IllegalArgumentException(name + " 必须是布尔值");
        return value.asBoolean();
    }

    /** 读取必填非空文本字段。 */
    private String required(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isString() || value.stringValue().isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.stringValue();
    }

    /** 读取只包含非空字符串的数组字段。 */
    private List<String> strings(JsonNode node) {
        if (!node.isArray()) throw new IllegalArgumentException("评测列表字段必须是数组");
        List<String> values = new ArrayList<>();
        node.forEach(value -> {
            if (!value.isString() || value.stringValue().isBlank()) {
                throw new IllegalArgumentException("评测列表元素不能为空");
            }
            values.add(value.stringValue());
        });
        return List.copyOf(values);
    }

    /** 将 UTF-8 文本拆成非空 JSONL 行。 */
    private List<String> lines(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).lines().filter(value -> !value.isBlank()).toList();
    }

    /** 生成跨用例和语料的稳定 SHA-256 十六进制摘要。 */
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

    /** 为重复检查规范化空白和大小写。 */
    private String normalize(String value) {
        return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /** 保存固定语料的三种查找视图。 */
    private record SourceCatalog(Map<String, String> byLegacyId,
                                 Map<String, String> byTypeAndTitle,
                                 Set<String> sourceKeys,
                                 Set<String> normalizedContents) { }

    /** 保存冻结清单中的数据集版本与内容哈希。 */
    private record DatasetManifest(String version, String contentSha256) { }
}
