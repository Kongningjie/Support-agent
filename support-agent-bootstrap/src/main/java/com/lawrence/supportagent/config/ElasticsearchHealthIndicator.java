package com.lawrence.supportagent.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** 通过 Elasticsearch 根端点执行轻量 readiness 探测。 */
@Component("elasticsearchHealthIndicator")
public class ElasticsearchHealthIndicator implements HealthIndicator {
    private final SupportAgentProperties properties;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    /** 保存 Elasticsearch 连接配置。 */
    public ElasticsearchHealthIndicator(SupportAgentProperties properties) {
        this.properties = properties;
    }

    /** 请求根端点并仅公开状态码，不公开连接地址或原始错误。 */
    @Override
    public Health health() {
        try {
            HttpRequest request = requestBuilder().GET().build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status >= 200 && status < 300
                    ? Health.up().withDetail("status", status).build()
                    : Health.down().withDetail("status", status).build();
        } catch (Exception exception) {
            return Health.down().withDetail("reason", "Elasticsearch 不可用").build();
        }
    }

    /** 构造健康探测请求；配置用户名时以 HTTP Basic 方式携带凭据。 */
    private HttpRequest.Builder requestBuilder() {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(properties.elasticsearch().url()))
                .timeout(Duration.ofSeconds(3));
        String username = properties.elasticsearch().username();
        if (username != null && !username.isBlank()) {
            String password = properties.elasticsearch().password() == null
                    ? "" : properties.elasticsearch().password();
            String credentials = Base64.getEncoder().encodeToString(
                    (username + ":" + password).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + credentials);
        }
        return builder;
    }
}
