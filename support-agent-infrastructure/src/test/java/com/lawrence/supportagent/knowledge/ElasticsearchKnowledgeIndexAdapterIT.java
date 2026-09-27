package com.lawrence.supportagent.knowledge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.elastic.clients.transport.rest5_client.low_level.Request;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.lawrence.supportagent.knowledge.port.KnowledgeIndexException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/** 使用带 ICU 插件的真实 Elasticsearch 验证索引创建、Bulk、校验和删除。 */
@Testcontainers
class ElasticsearchKnowledgeIndexAdapterIT {
    private static final ImageFromDockerfile IMAGE = new ImageFromDockerfile(
            "support-agent-elasticsearch-it", false).withDockerfile(
            Path.of("..", "deploy", "elasticsearch", "Dockerfile").toAbsolutePath());

    @Container
    private static final GenericContainer<?> ELASTICSEARCH = new GenericContainer<>(IMAGE)
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/_cluster/health").forPort(9200).forStatusCode(200));

    /** 验证阶段 3 完整索引生命周期及 ICU 物理索引惰性创建。 */
    @Test
    void shouldIndexVerifyAndDeleteManagedDocumentVersion() throws Exception {
        URI endpoint = URI.create("http://" + ELASTICSEARCH.getHost() + ":"
                + ELASTICSEARCH.getMappedPort(9200));
        try (Rest5Client client = Rest5Client.builder(endpoint).build()) {
            ElasticsearchKnowledgeIndexAdapter adapter = new ElasticsearchKnowledgeIndexAdapter(
                    client, JsonMapper.builder().findAndAddModules().build(),
                    "support_knowledge_v1", "support_knowledge_current", "", "");
            adapter.ensureReady();
            adapter.ensureReady();
            IndexedKnowledgeChunk chunk = chunk();

            adapter.indexChunks(List.of(chunk));

            UUID spaceA = UUID.randomUUID();
            UUID spaceB = UUID.randomUUID();
            IndexedKnowledgeChunk restrictedA = chunk(spaceA, 8L);
            IndexedKnowledgeChunk restrictedB = chunk(spaceB, 9L);
            adapter.indexChunks(List.of(restrictedA, restrictedB));
            assertEquals(List.of(chunk.chunkId()), adapter.searchBm25("连接参数",
                    Set.of(com.lawrence.supportagent.knowledgespace.KnowledgeSpace.GLOBAL_SPACE_ID),
                    10).stream().map(value -> value.chunkId()).toList());
            assertEquals(List.of(chunk.chunkId()), adapter.searchVector(
                    Collections.nCopies(1024, 0.01D),
                    Set.of(com.lawrence.supportagent.knowledgespace.KnowledgeSpace.GLOBAL_SPACE_ID),
                    10, 20, 0.0).stream().map(value -> value.chunkId()).toList());
            Set<UUID> allowedA = Set.of(
                    com.lawrence.supportagent.knowledgespace.KnowledgeSpace.GLOBAL_SPACE_ID, spaceA);
            Set<UUID> allowedB = Set.of(
                    com.lawrence.supportagent.knowledgespace.KnowledgeSpace.GLOBAL_SPACE_ID, spaceB);
            assertEquals(Set.of(chunk.chunkId(), restrictedA.chunkId()),
                    adapter.searchBm25("连接参数", allowedA, 10).stream()
                            .map(value -> value.chunkId()).collect(java.util.stream.Collectors.toSet()));
            assertEquals(Set.of(chunk.chunkId(), restrictedB.chunkId()),
                    adapter.searchBm25("连接参数", allowedB, 10).stream()
                            .map(value -> value.chunkId()).collect(java.util.stream.Collectors.toSet()));
            assertEquals(Set.of(chunk.chunkId(), restrictedA.chunkId()),
                    adapter.searchVector(Collections.nCopies(1024, 0.01D), allowedA,
                                    10, 20, 0.0).stream().map(value -> value.chunkId())
                            .collect(java.util.stream.Collectors.toSet()));
            assertEquals(Set.of(chunk.chunkId(), restrictedB.chunkId()),
                    adapter.searchVector(Collections.nCopies(1024, 0.01D), allowedB,
                                    10, 20, 0.0).stream().map(value -> value.chunkId())
                            .collect(java.util.stream.Collectors.toSet()));

            assertTrue(adapter.verifyVersion("MANAGED_DOCUMENT", 7L, 2L,
                    List.of(chunk.contentHash())));
            assertTrue(adapter.sourceExists("MANAGED_DOCUMENT", 7L));
            adapter.deleteVersion("MANAGED_DOCUMENT", 7L, 2L);
            assertFalse(adapter.sourceExists("MANAGED_DOCUMENT", 7L));

            client.performRequest(new Request("DELETE", "/support_knowledge_v1"));
            adapter.ensureReady();
            assertFalse(adapter.sourceExists("MANAGED_DOCUMENT", 7L));
            adapter.indexChunks(List.of(chunk));
            assertTrue(adapter.verifyVersion("MANAGED_DOCUMENT", 7L, 2L,
                    List.of(chunk.contentHash())));

            Request createOther = new Request("PUT", "/support_knowledge_other");
            createOther.setJsonEntity("{}");
            client.performRequest(createOther);
            Request addConflictingAlias = new Request("POST", "/_aliases");
            addConflictingAlias.setJsonEntity("""
                    {"actions":[{"add":{"index":"support_knowledge_other",
                    "alias":"support_knowledge_current"}}]}
                    """);
            client.performRequest(addConflictingAlias);
            KnowledgeIndexException conflict = assertThrows(KnowledgeIndexException.class,
                    adapter::ensureReady);
            assertEquals("KNOWLEDGE_ALIAS_CONFLICT", conflict.errorCode());
            assertFalse(conflict.retryable());

            Request leaveSingleOldAlias = new Request("POST", "/_aliases");
            leaveSingleOldAlias.setJsonEntity("""
                    {"actions":[{"remove":{"index":"support_knowledge_v1",
                    "alias":"support_knowledge_current"}}]}
                    """);
            client.performRequest(leaveSingleOldAlias);
            adapter.ensureReady();
        }
    }

    /** 创建满足冻结映射的测试分块。 */
    private IndexedKnowledgeChunk chunk() {
        return chunk(com.lawrence.supportagent.knowledgespace.KnowledgeSpace.GLOBAL_SPACE_ID, 7L);
    }

    /** 创建属于指定空间和来源的测试分块，用于验证召回前空间过滤。 */
    private IndexedKnowledgeChunk chunk(UUID spaceId, long sourceId) {
        return new IndexedKnowledgeChunk("MANAGED_DOCUMENT:" + sourceId + ":2:0",
                "MANAGED_DOCUMENT", sourceId, 2L, spaceId, 0,
                "MySQL 排障", "MySQL 排障 > 连接", "检查连接参数。",
                List.of(new ExactTerm(ExactTermType.ERROR_CODE, "CONNECTION_ERROR",
                        "CONNECTION_ERROR", 0, 16)), "hash-001",
                Collections.nCopies(1024, 0.01D), Instant.parse("2026-09-07T01:00:00Z"),
                Instant.parse("2026-09-07T01:00:01Z"), "chunk-v1", "exact-v1");
    }
}
