package com.lawrence.supportagent.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 仅在明确提供真实密钥时验证 DashScope Embedding 在线协议。 */
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
class DashScopeEmbeddingModelAdapterOnlineIT {
    /** 验证文档和查询输入均可获得有限的 1024 维向量。 */
    @Test
    void shouldCreateDocumentAndQueryEmbeddings() {
        try (DashScopeEmbeddingModelAdapter adapter = new DashScopeEmbeddingModelAdapter(
                System.getenv("DASHSCOPE_API_KEY"), "text-embedding-v4", baseUrl())) {
            List<Double> document = adapter.embedDocuments(
                    List.of("Support Agent 在线 Embedding 协议验证。"), null).getFirst();
            List<Double> query = adapter.embedQuery("如何验证 Embedding？");

            assertEquals(1024, document.size());
            assertEquals(1024, query.size());
            assertTrue(document.stream().allMatch(value -> value != null && Double.isFinite(value)));
            assertTrue(query.stream().allMatch(value -> value != null && Double.isFinite(value)));
        }
    }

    /** 返回在线环境显式地址，未配置时使用 DashScope 公共地址。 */
    private String baseUrl() {
        return System.getenv().getOrDefault("DASHSCOPE_HTTP_BASE_URL",
                "https://dashscope.aliyuncs.com/api/v1");
    }
}
