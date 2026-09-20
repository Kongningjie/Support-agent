package com.lawrence.supportagent.agent.model;

import com.lawrence.supportagent.memory.MemoryType;
import com.lawrence.supportagent.memory.UserMemoryCandidate;
import com.lawrence.supportagent.memory.port.UserMemoryCandidatePort;
import com.lawrence.supportagent.model.ModelInvocationException;
import io.agentscope.core.formatter.JsonSchema;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 使用独立 DashScope 模型从单轮用户消息生成严格结构化的长期记忆候选。 */
public class DashScopeUserMemoryCandidateAdapter implements UserMemoryCandidatePort {
    private final Model model;
    private final ObjectMapper mapper;
    private final Duration timeout;
    private final GenerateOptions generateOptions;
    private final PromptTemplate prompt = new PromptTemplate("/prompts/user-memory-candidate.md");

    /** 使用独立模型名称、超时和输出上限创建候选适配器。 */
    public DashScopeUserMemoryCandidateAdapter(String apiKey, String modelName, String baseUrl,
                                               ObjectMapper mapper, Duration timeout,
                                               int maximumOutputTokens) {
        this.generateOptions = options(maximumOutputTokens);
        this.model = OpenAIChatModel.builder().apiKey(apiKey).modelName(modelName)
                .baseUrl(AgentScopeDashScopeBaseUrl.openAiCompatible(baseUrl)).stream(false)
                .generateOptions(generateOptions).build();
        this.mapper = mapper;
        this.timeout = timeout;
    }

    /** {@inheritDoc} */
    @Override public List<UserMemoryCandidate> propose(String userMessage) {
        String rendered = prompt.render(Map.of("USER_MESSAGE", userMessage));
        Msg request = Msg.builder().role(MsgRole.USER).textContent(rendered).build();
        StringBuilder output = new StringBuilder();
        try {
            model.stream(List.of(request), List.of(), generateOptions).doOnNext(response ->
                    response.getContent().stream().filter(TextBlock.class::isInstance)
                            .map(TextBlock.class::cast).map(TextBlock::getText)
                            .filter(text -> text != null && !text.isEmpty()).forEach(output::append))
                    .blockLast(timeout);
            return parse(output.toString());
        } catch (ModelInvocationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ModelInvocationException("MEMORY_CANDIDATE_MODEL_UNAVAILABLE",
                    "长期记忆候选模型调用失败", false, exception);
        }
    }

    /** 解析只允许包含 candidates 数组的严格 JSON。 */
    List<UserMemoryCandidate> parse(String value) {
        try {
            JsonNode root = mapper.readTree(value);
            JsonNode candidates = root.path("candidates");
            if (!root.isObject() || root.size() != 1 || !candidates.isArray()
                    || candidates.size() > 3) {
                throw new IllegalArgumentException("候选根结构不合法");
            }
            List<UserMemoryCandidate> result = new ArrayList<>();
            candidates.forEach(item -> {
                if (!item.isObject() || item.size() != 2
                        || !item.path("memoryType").isString()
                        || !item.path("content").isString()) {
                    throw new IllegalArgumentException("候选字段不合法");
                }
                result.add(new UserMemoryCandidate(
                        MemoryType.valueOf(item.path("memoryType").stringValue()),
                        item.path("content").stringValue()));
            });
            return List.copyOf(result);
        } catch (RuntimeException exception) {
            throw new ModelInvocationException("MEMORY_CANDIDATE_SCHEMA_INVALID",
                    "长期记忆候选结构不合法", false, exception);
        }
    }

    /** 创建关闭思考模式并启用严格 JSON Schema 的生成参数。 */
    private GenerateOptions options(int maximumOutputTokens) {
        Map<String, Object> candidate = Map.of("type", "object", "properties", Map.of(
                        "memoryType", Map.of("type", "string", "enum", List.of(
                                "PREFERENCE", "CONSTRAINT", "ENVIRONMENT")),
                        "content", Map.of("type", "string", "minLength", 1, "maxLength", 500)),
                "required", List.of("memoryType", "content"), "additionalProperties", false);
        JsonSchema schema = JsonSchema.builder().name("user_memory_candidates")
                .description("用户可确认的跨会话长期记忆候选")
                .schema(Map.of("type", "object", "properties", Map.of(
                                "candidates", Map.of("type", "array", "maxItems", 3,
                                        "items", candidate)),
                        "required", List.of("candidates"), "additionalProperties", false))
                .strict(true).build();
        return GenerateOptions.builder().temperature(0.0).maxTokens(maximumOutputTokens)
                .additionalBodyParam("enable_thinking", false)
                .responseFormat(ResponseFormat.jsonSchema(schema)).build();
    }
}
