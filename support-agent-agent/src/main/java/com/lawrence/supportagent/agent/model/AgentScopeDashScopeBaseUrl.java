package com.lawrence.supportagent.agent.model;

/** 规范化传给 AgentScope DashScope Chat 适配器的服务根地址。 */
final class AgentScopeDashScopeBaseUrl {
    private static final String API_V1_SUFFIX = "/api/v1";
    private static final String COMPATIBLE_V1_SUFFIX = "/compatible-mode/v1";

    /** 禁止实例化仅承载地址规范化规则的类型。 */
    private AgentScopeDashScopeBaseUrl() {
    }

    /**
     * 移除配置地址末尾的斜杠及 {@code /api/v1}，避免 AgentScope 再次拼接固定路径时产生重复路径。
     *
     * @param baseUrl DashScope 原生 API 根地址或主机地址
     * @return AgentScope 所需的不含 {@code /api/v1} 的主机地址
     */
    static String normalize(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("DashScope Base URL 不能为空");
        }
        String normalized = baseUrl.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith(API_V1_SUFFIX)) {
            normalized = normalized.substring(0, normalized.length() - API_V1_SUFFIX.length());
        }
        return normalized;
    }

    /**
     * 将 DashScope 原生地址或兼容地址统一为 OpenAI 兼容根地址。
     *
     * @param baseUrl 工作空间或公共 DashScope API 根地址
     * @return 以 {@code /compatible-mode/v1} 结尾的兼容地址
     */
    static String openAiCompatible(String baseUrl) {
        String host = normalize(baseUrl);
        if (host.endsWith("/compatible-mode/v1")) return host;
        return host + COMPATIBLE_V1_SUFFIX;
    }
}
