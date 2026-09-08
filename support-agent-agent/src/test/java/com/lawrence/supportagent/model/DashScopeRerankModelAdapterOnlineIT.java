package com.lawrence.supportagent.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.lawrence.supportagent.model.RerankModelPort.RerankDocument;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 仅在明确提供真实密钥时验证 DashScope Rerank 在线协议。 */
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
class DashScopeRerankModelAdapterOnlineIT {
    /** 验证重排结果完整返回输入分块标识和有限相关性分数。 */
    @Test
    void shouldRerankDocuments() {
        DashScopeRerankModelAdapter adapter = new DashScopeRerankModelAdapter(
                System.getenv("DASHSCOPE_API_KEY"), "gte-rerank-v2", baseUrl());

        var scores = adapter.rerank("MySQL 连接失败", List.of(
                new RerankDocument("chunk-1", "MySQL 连接故障排查"),
                new RerankDocument("chunk-2", "Redis 缓存清理说明")));

        assertThat(scores).hasSize(2);
        assertThat(scores).extracting(value -> value.chunkId())
                .containsExactlyInAnyOrder("chunk-1", "chunk-2");
        assertThat(scores).allMatch(value -> Double.isFinite(value.score()));
    }

    /** 返回在线环境显式地址，未配置时使用 DashScope 公共地址。 */
    private String baseUrl() {
        return System.getenv().getOrDefault("DASHSCOPE_HTTP_BASE_URL",
                "https://dashscope.aliyuncs.com/api/v1");
    }
}
