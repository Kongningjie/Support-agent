package com.lawrence.supportagent.knowledge;

import co.elastic.clients.transport.rest5_client.low_level.Request;
import co.elastic.clients.transport.rest5_client.low_level.Response;
import co.elastic.clients.transport.rest5_client.low_level.ResponseException;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.lawrence.supportagent.knowledge.port.KnowledgeIndexException;
import com.lawrence.supportagent.knowledge.port.KnowledgeIndexPort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 使用 Elasticsearch REST5 客户端维护版本化知识索引和固定业务别名。 */
public class ElasticsearchKnowledgeIndexAdapter implements KnowledgeIndexPort {
    private static final String ICU_ANALYZER = "support_icu";
    private final Rest5Client client;
    private final ObjectMapper objectMapper;
    private final String indexName;
    private final String aliasName;
    private final String authorization;

    /** 注入客户端、JSON 编解码器、冻结索引名称、别名和可选基础认证。 */
    public ElasticsearchKnowledgeIndexAdapter(Rest5Client client, ObjectMapper objectMapper,
                                               String indexName, String aliasName,
                                               String username, String password) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.indexName = requiredName(indexName, "物理索引名");
        this.aliasName = requiredName(aliasName, "业务别名");
        this.authorization = basicAuthorization(username, password);
    }

    /** {@inheritDoc} */
    @Override
    public void ensureReady() {
        JsonNode index = getOptional("/" + indexName);
        if (index == null) {
            try {
                perform("PUT", "/" + indexName, indexDefinition(), false);
            } catch (KnowledgeIndexException creationFailure) {
                JsonNode concurrentlyCreated = getOptional("/" + indexName);
                if (concurrentlyCreated == null) {
                    throw creationFailure;
                }
                validateIndex(concurrentlyCreated);
            }
        } else {
            validateIndex(index);
        }
        JsonNode aliases = getOptional("/_alias/" + aliasName);
        if (aliases == null) {
            perform("POST", "/_aliases", Map.of("actions", List.of(
                    Map.of("add", Map.of("index", indexName, "alias", aliasName)))), false);
            return;
        }
        if (aliases.size() != 1 || !aliases.has(indexName)) {
            throw failure("KNOWLEDGE_ALIAS_CONFLICT",
                    "Elasticsearch 业务别名已指向其他物理索引", false, null);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void indexChunks(List<IndexedKnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("待索引分块不能为空");
        }
        StringBuilder body = new StringBuilder();
        for (IndexedKnowledgeChunk chunk : chunks) {
            appendJsonLine(body, Map.of("index", Map.of(
                    "_index", aliasName, "_id", chunk.chunkId())));
            appendJsonLine(body, chunkDocument(chunk));
        }
        Request request = request("POST", "/_bulk?refresh=wait_for");
        request.setEntity(new StringEntity(body.toString(), ContentType.create(
                "application/x-ndjson", StandardCharsets.UTF_8)));
        JsonNode response = perform(request, true);
        JsonNode items = response.path("items");
        if (response.path("errors").asBoolean(true) || !items.isArray()
                || items.size() != chunks.size() || !bulkItemsSucceeded(items)) {
            throw failure("KNOWLEDGE_BULK_ITEM_FAILED",
                    "Elasticsearch Bulk 写入存在失败分块", true, null);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean verifyVersion(String sourceType, long sourceId, long sourceVersion,
                                 List<String> expectedContentHashes) {
        Map<String, Object> query = versionQuery(sourceType, sourceId, sourceVersion);
        Map<String, Object> body = Map.of("size", expectedContentHashes.size() + 1,
                "track_total_hits", true, "_source", List.of("contentHash"), "query", query);
        JsonNode response = perform("POST", "/" + aliasName + "/_search", body, true);
        JsonNode hits = response.path("hits").path("hits");
        if (!hits.isArray() || hits.size() != expectedContentHashes.size()) {
            return false;
        }
        List<String> actual = new ArrayList<>();
        hits.forEach(hit -> actual.add(hit.path("_source").path("contentHash").asText()));
        return multiset(actual).equals(multiset(expectedContentHashes));
    }

    /** {@inheritDoc} */
    @Override
    public void deleteVersion(String sourceType, long sourceId, long sourceVersion) {
        deleteByQuery(versionQuery(sourceType, sourceId, sourceVersion));
    }

    /** {@inheritDoc} */
    @Override
    public void deleteSource(String sourceType, long sourceId) {
        deleteByQuery(sourceQuery(sourceType, sourceId));
    }

    /** {@inheritDoc} */
    @Override
    public boolean sourceExists(String sourceType, long sourceId) {
        JsonNode response = perform("POST", "/" + aliasName + "/_search",
                Map.of("size", 0, "track_total_hits", true,
                        "query", sourceQuery(sourceType, sourceId)), true);
        return response.path("hits").path("total").path("value").asLong() > 0;
    }

    /** 返回包含 ICU 分析器、精确词嵌套字段和 1024 维向量的冻结索引定义。 */
    private Map<String, Object> indexDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("chunkId", Map.of("type", "keyword"));
        properties.put("sourceType", Map.of("type", "keyword"));
        properties.put("sourceId", Map.of("type", "long"));
        properties.put("sourceVersion", Map.of("type", "long"));
        properties.put("chunkIndex", Map.of("type", "integer"));
        properties.put("title", Map.of("type", "text", "analyzer", ICU_ANALYZER));
        properties.put("headingPath", Map.of("type", "text", "analyzer", ICU_ANALYZER));
        properties.put("content", Map.of("type", "text", "analyzer", ICU_ANALYZER));
        properties.put("exactTerms", Map.of("type", "nested", "properties", Map.of(
                "type", Map.of("type", "keyword"),
                "normalizedValue", Map.of("type", "keyword"),
                "displayValue", Map.of("type", "keyword", "index", false),
                "sourceOffsetStart", Map.of("type", "integer"),
                "sourceOffsetEnd", Map.of("type", "integer"))));
        properties.put("contentHash", Map.of("type", "keyword"));
        properties.put("embedding", Map.of("type", "dense_vector", "dims", 1024,
                "index", true, "similarity", "cosine"));
        properties.put("publishedAt", Map.of("type", "date"));
        properties.put("indexedAt", Map.of("type", "date"));
        properties.put("chunkStrategyVersion", Map.of("type", "keyword"));
        properties.put("exactTermExtractorVersion", Map.of("type", "keyword"));
        return Map.of("settings", Map.of("analysis", Map.of("analyzer", Map.of(
                        ICU_ANALYZER, Map.of("type", "custom", "tokenizer", "icu_tokenizer",
                                "filter", List.of("icu_folding", "lowercase"))))),
                "mappings", Map.of("dynamic", "strict", "properties", properties));
    }

    /** 校验已存在索引仍符合阶段 3 的关键映射契约。 */
    private void validateIndex(JsonNode response) {
        JsonNode root = response.path(indexName);
        JsonNode mapping = root.path("mappings").path("properties");
        JsonNode analyzer = root.path("settings").path("index").path("analysis")
                .path("analyzer").path(ICU_ANALYZER);
        boolean valid = "strict".equals(root.path("mappings").path("dynamic").asText())
                && "icu_tokenizer".equals(analyzer.path("tokenizer").asText())
                && "support_icu".equals(mapping.path("content").path("analyzer").asText())
                && "nested".equals(mapping.path("exactTerms").path("type").asText())
                && mapping.path("embedding").path("dims").asInt() == 1024;
        if (!valid) {
            throw failure("KNOWLEDGE_INDEX_MAPPING_MISMATCH",
                    "Elasticsearch 物理索引映射与阶段 3 契约不一致", false, null);
        }
    }

    /** 把应用层分块转换为不包含空字段的索引文档。 */
    private Map<String, Object> chunkDocument(IndexedKnowledgeChunk chunk) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("chunkId", chunk.chunkId());
        value.put("sourceType", chunk.sourceType());
        value.put("sourceId", chunk.sourceId());
        value.put("sourceVersion", chunk.sourceVersion());
        value.put("chunkIndex", chunk.chunkIndex());
        value.put("title", chunk.title());
        value.put("headingPath", chunk.headingPath());
        value.put("content", chunk.content());
        value.put("exactTerms", chunk.exactTerms());
        value.put("contentHash", chunk.contentHash());
        value.put("embedding", chunk.embedding());
        value.put("publishedAt", chunk.publishedAt());
        value.put("indexedAt", chunk.indexedAt());
        value.put("chunkStrategyVersion", chunk.chunkStrategyVersion());
        value.put("exactTermExtractorVersion", chunk.exactTermExtractorVersion());
        return value;
    }

    /** 构造来源版本的严格布尔过滤查询。 */
    private Map<String, Object> versionQuery(String sourceType, long sourceId, long sourceVersion) {
        List<Object> filters = new ArrayList<>(sourceFilters(sourceType, sourceId));
        filters.add(Map.of("term", Map.of("sourceVersion", sourceVersion)));
        return Map.of("bool", Map.of("filter", filters));
    }

    /** 构造来源记录的严格布尔过滤查询。 */
    private Map<String, Object> sourceQuery(String sourceType, long sourceId) {
        return Map.of("bool", Map.of("filter", sourceFilters(sourceType, sourceId)));
    }

    /** 返回来源类型和来源 ID 两个公共过滤条件。 */
    private List<Object> sourceFilters(String sourceType, long sourceId) {
        return List.of(Map.of("term", Map.of("sourceType", sourceType)),
                Map.of("term", Map.of("sourceId", sourceId)));
    }

    /** 执行删除查询并等待刷新，同时拒绝版本冲突或执行超时。 */
    private void deleteByQuery(Map<String, Object> query) {
        JsonNode response = perform("POST", "/" + aliasName
                + "/_delete_by_query?refresh=true&conflicts=proceed", Map.of("query", query), true);
        if (response.path("timed_out").asBoolean(false)
                || response.path("version_conflicts").asLong() > 0
                || response.path("failures").size() > 0) {
            throw failure("KNOWLEDGE_DELETE_INCOMPLETE",
                    "Elasticsearch 来源分块删除不完整", true, null);
        }
    }

    /** 可选读取资源；仅把 404 解释为不存在。 */
    private JsonNode getOptional(String endpoint) {
        try {
            return perform(request("GET", endpoint), true);
        } catch (KnowledgeIndexException exception) {
            if ("KNOWLEDGE_RESOURCE_NOT_FOUND".equals(exception.errorCode())) {
                return null;
            }
            throw exception;
        }
    }

    /** 序列化 JSON 请求并执行。 */
    private JsonNode perform(String method, String endpoint, Object body, boolean retryable) {
        Request request = request(method, endpoint);
        try {
            request.setJsonEntity(objectMapper.writeValueAsString(body));
        } catch (JacksonException exception) {
            throw failure("KNOWLEDGE_JSON_ENCODING_FAILED", "知识索引请求编码失败", false, exception);
        }
        return perform(request, retryable);
    }

    /** 执行 REST 请求并只返回安全解析后的响应结构。 */
    private JsonNode perform(Request request, boolean retryable) {
        try {
            Response response = client.performRequest(request);
            int status = response.getStatusCode();
            if (status == 404) {
                throw failure("KNOWLEDGE_RESOURCE_NOT_FOUND",
                        "Elasticsearch 资源不存在", false, null);
            }
            if (status < 200 || status >= 300) {
                throw failure("KNOWLEDGE_ELASTICSEARCH_REJECTED",
                        "Elasticsearch 拒绝知识索引请求",
                        status == 429 || status >= 500, null);
            }
            String json = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            return json == null || json.isBlank() ? objectMapper.createObjectNode()
                    : objectMapper.readTree(json);
        } catch (ResponseException exception) {
            int status = exception.getResponse().getStatusCode();
            if (status == 404) {
                throw failure("KNOWLEDGE_RESOURCE_NOT_FOUND",
                        "Elasticsearch 资源不存在", false, exception);
            }
            throw failure("KNOWLEDGE_ELASTICSEARCH_REJECTED",
                    "Elasticsearch 拒绝知识索引请求", status == 429 || status >= 500, exception);
        } catch (IOException exception) {
            throw failure("KNOWLEDGE_ELASTICSEARCH_UNAVAILABLE",
                    "Elasticsearch 暂时不可用", retryable, exception);
        } catch (org.apache.hc.core5.http.ParseException exception) {
            throw failure("KNOWLEDGE_ELASTICSEARCH_RESPONSE_INVALID",
                    "Elasticsearch 响应无法解析", false, exception);
        }
    }

    /** 创建携带可选认证信息的请求。 */
    private Request request(String method, String endpoint) {
        Request request = new Request(method, endpoint);
        if (authorization != null) {
            request.setOptions(request.getOptions().toBuilder()
                    .addHeader("Authorization", authorization));
        }
        return request;
    }

    /** 把单个对象追加为 Bulk 所需的 NDJSON 行。 */
    private void appendJsonLine(StringBuilder target, Object value) {
        try {
            target.append(objectMapper.writeValueAsString(value)).append('\n');
        } catch (JacksonException exception) {
            throw failure("KNOWLEDGE_JSON_ENCODING_FAILED", "知识索引请求编码失败", false, exception);
        }
    }

    /** 逐项确认 Bulk index 动作具备成功状态且没有 error 字段。 */
    private boolean bulkItemsSucceeded(JsonNode items) {
        for (JsonNode item : items) {
            JsonNode index = item.path("index");
            int status = index.path("status").asInt(0);
            if (status < 200 || status >= 300 || index.has("error")) {
                return false;
            }
        }
        return true;
    }

    /** 统计哈希多重集合，避免仅比较无序集合遗漏重复项。 */
    private Map<String, Integer> multiset(List<String> values) {
        Map<String, Integer> counts = new HashMap<>();
        values.forEach(value -> counts.merge(value, 1, Integer::sum));
        return counts;
    }

    /** 生成可选 HTTP Basic 认证头，不在错误或日志中回显凭据。 */
    private String basicAuthorization(String username, String password) {
        if (username == null || username.isBlank()) {
            return null;
        }
        String credentials = username + ":" + (password == null ? "" : password);
        return "Basic " + Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8));
    }

    /** 校验索引及别名为非空安全名称。 */
    private String requiredName(String value, String fieldName) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9_-]{0,254}")) {
            throw new IllegalArgumentException(fieldName + "不合法");
        }
        return value;
    }

    /** 创建不会包含 Elasticsearch 原始响应的稳定端口异常。 */
    private KnowledgeIndexException failure(String code, String message, boolean retryable,
                                            Throwable cause) {
        return new KnowledgeIndexException(code, message, retryable, cause);
    }
}
