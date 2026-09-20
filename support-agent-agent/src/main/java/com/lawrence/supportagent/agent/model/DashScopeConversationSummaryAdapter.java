package com.lawrence.supportagent.agent.model;

import com.lawrence.supportagent.chat.ConversationSummary;
import com.lawrence.supportagent.chat.ConversationSummaryKeyEntity;
import com.lawrence.supportagent.chat.port.ConversationStorePort.CompletedTurn;
import com.lawrence.supportagent.knowledge.ExactTermType;
import com.lawrence.supportagent.model.ConversationSummaryPort;
import com.lawrence.supportagent.model.ModelInvocationException;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort.ModelOperation;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort.Operation;
import io.agentscope.core.formatter.JsonSchema;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 使用独立 DashScope 模型生成严格结构化的单会话滚动摘要。 */
public class DashScopeConversationSummaryAdapter implements ConversationSummaryPort {
    private final Model model;
    private final ObjectMapper mapper;
    private final Duration timeout;
    private final int maximumOutputTokens;
    private final OptimizationTelemetryPort telemetry;
    private final Consumer<SummaryUsage> usageObserver;
    private final PromptTemplate prompt = new PromptTemplate("/prompts/conversation-summary.md");

    /** 使用独立模型名称、超时和输出预算创建摘要适配器。 */
    public DashScopeConversationSummaryAdapter(String apiKey, String modelName, String baseUrl,
                                               ObjectMapper mapper, Duration timeout,
                                               int maximumOutputTokens) {
        this(apiKey, modelName, baseUrl, mapper, timeout, maximumOutputTokens,
                OptimizationTelemetryPort.noOp(), ignored -> { });
    }

    /** 使用低基数遥测和可选评测观察器创建摘要适配器。 */
    public DashScopeConversationSummaryAdapter(String apiKey, String modelName, String baseUrl,
                                               ObjectMapper mapper, Duration timeout,
                                               int maximumOutputTokens,
                                               OptimizationTelemetryPort telemetry,
                                               Consumer<SummaryUsage> usageObserver) {
        this.model = OpenAIChatModel.builder().apiKey(apiKey).modelName(modelName)
                .baseUrl(AgentScopeDashScopeBaseUrl.openAiCompatible(baseUrl)).stream(false)
                .generateOptions(options(maximumOutputTokens)).build();
        this.mapper = mapper;
        this.timeout = timeout;
        this.maximumOutputTokens = maximumOutputTokens;
        this.telemetry = telemetry == null ? OptimizationTelemetryPort.noOp() : telemetry;
        this.usageObserver = usageObserver == null ? ignored -> { } : usageObserver;
    }

    /** {@inheritDoc} */
    @Override
    public ConversationSummary summarize(ConversationSummary previousSummary,
                                         List<CompletedTurn> sourceTurns,
                                         long nextSummaryVersion,
                                         long coveredThroughVersion) {
        String rendered = prompt.render(Map.of(
                "PREVIOUS_SUMMARY", previousSummary == null ? "无" : write(previousSummary),
                "SOURCE_TURNS", renderTurns(sourceTurns)));
        Msg request = Msg.builder().role(MsgRole.USER).textContent(rendered).build();
        StringBuilder output = new StringBuilder();
        AtomicReference<ChatUsage> usage = new AtomicReference<>();
        long started = System.nanoTime();
        try {
            model.stream(List.of(request), List.of(), options(maximumOutputTokens))
                    .doOnNext(response -> {
                        if (response.getUsage() != null) {
                            usage.set(response.getUsage());
                        }
                        response.getContent().stream().filter(TextBlock.class::isInstance)
                                .map(TextBlock.class::cast).map(TextBlock::getText)
                                .filter(text -> text != null && !text.isEmpty())
                                .forEach(output::append);
                    })
                    .blockLast(timeout);
            ConversationSummary result = parse(output.toString(), nextSummaryVersion,
                    coveredThroughVersion);
            recordUsage(usage.get(), rendered.length(), output.length(), started, true);
            return result;
        } catch (ModelInvocationException exception) {
            recordUsage(usage.get(), rendered.length(), output.length(), started, false);
            throw exception;
        } catch (RuntimeException exception) {
            recordUsage(usage.get(), rendered.length(), output.length(), started, false);
            throw new ModelInvocationException("SUMMARY_MODEL_UNAVAILABLE",
                    "会话摘要模型调用失败", true, exception);
        }
    }

    /** 记录摘要模型的聚合 Token、字符和耗时，并通知在线评测观察器。 */
    private void recordUsage(ChatUsage usage, long inputCharacters, long outputCharacters,
                             long started, boolean succeeded) {
        long inputTokens = usage == null ? 0 : usage.getInputTokens();
        long outputTokens = usage == null ? 0 : usage.getOutputTokens();
        long durationMs = (System.nanoTime() - started) / 1_000_000;
        telemetry.recordUsage(ModelOperation.SUMMARY, inputTokens, outputTokens, 1,
                inputCharacters + outputCharacters);
        telemetry.recordDuration(Operation.CONVERSATION_SUMMARY, durationMs, succeeded);
        try {
            usageObserver.accept(new SummaryUsage(inputTokens, outputTokens, inputCharacters,
                    outputCharacters, durationMs, succeeded));
        } catch (RuntimeException ignored) {
            // 评测观察器不得改变生产摘要调用的成功或失败语义。
        }
    }

    /** 解析严格 JSON 内容字段，并由服务端写入不可由模型决定的版本元数据。 */
    private ConversationSummary parse(String value, long summaryVersion,
                                      long coveredThroughVersion) {
        try {
            JsonNode node = mapper.readTree(value);
            if (!node.isObject() || node.size() != 5) {
                throw new IllegalArgumentException("摘要字段数量不合法");
            }
            return new ConversationSummary(ConversationSummary.SCHEMA_VERSION,
                    summaryVersion, coveredThroughVersion, strings(node, "goals"),
                    strings(node, "confirmedDecisions"), strings(node, "constraints"),
                    strings(node, "unresolvedQuestions"), entities(node, "keyEntities"));
        } catch (RuntimeException exception) {
            throw new ModelInvocationException("SUMMARY_SCHEMA_INVALID",
                    "会话摘要结构不合法", false, exception);
        }
    }

    /** 读取必填字符串数组并拒绝对象、数字或空数组节点。 */
    private List<String> strings(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        if (!node.isArray()) {
            throw new IllegalArgumentException(field + " 必须是数组");
        }
        List<String> result = new ArrayList<>();
        node.forEach(item -> {
            if (!item.isString()) {
                throw new IllegalArgumentException(field + " 只能包含字符串");
            }
            result.add(item.stringValue());
        });
        return List.copyOf(result);
    }

    /** 读取关键实体数组，并校验每项只含类型、规范值和来源版本。 */
    private List<ConversationSummaryKeyEntity> entities(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        if (!node.isArray()) {
            throw new IllegalArgumentException(field + " 必须是数组");
        }
        List<ConversationSummaryKeyEntity> result = new ArrayList<>();
        node.forEach(item -> {
            if (!item.isObject() || item.size() != 3) {
                throw new IllegalArgumentException("关键实体字段不完整");
            }
            List<Long> versions = new ArrayList<>();
            JsonNode sourceVersions = item.path("sourceTurnVersions");
            if (!sourceVersions.isArray()) {
                throw new IllegalArgumentException("关键实体来源版本必须是数组");
            }
            sourceVersions.forEach(version -> {
                if (!version.isIntegralNumber()) {
                    throw new IllegalArgumentException("关键实体来源版本必须是整数");
                }
                versions.add(version.asLong());
            });
            result.add(new ConversationSummaryKeyEntity(
                    ExactTermType.valueOf(item.path("type").stringValue()),
                    item.path("normalizedValue").stringValue(), versions));
        });
        return List.copyOf(result);
    }

    /** 将来源轮次渲染为带成功版本的摘要模型输入。 */
    private String renderTurns(List<CompletedTurn> turns) {
        return turns.stream().map(turn -> "[TURN " + turn.conversationVersion() + "]\n用户："
                + turn.userMessage() + "\n助手：" + turn.answer()).reduce("",
                (left, right) -> left.isEmpty() ? right : left + "\n\n" + right);
    }

    /** 序列化已有结构化摘要，失败时返回稳定模型异常且不泄露正文。 */
    private String write(ConversationSummary summary) {
        try {
            return mapper.writeValueAsString(summary);
        } catch (RuntimeException exception) {
            throw new ModelInvocationException("SUMMARY_SERIALIZATION_FAILED",
                    "会话摘要无法序列化", false, exception);
        }
    }

    /** 创建关闭思考模式并启用严格 JSON Schema 的生成参数。 */
    private GenerateOptions options(int maxTokens) {
        Map<String, Object> entity = Map.of("type", "object", "properties", Map.of(
                        "type", Map.of("type", "string", "enum", List.of(
                                "ERROR_CODE", "VERSION", "COMMAND", "CONFIG_KEY", "FILE_PATH",
                                "TICKET_NO", "CLASS_OR_PACKAGE", "URL_OR_ENDPOINT")),
                        "normalizedValue", Map.of("type", "string", "minLength", 1),
                        "sourceTurnVersions", Map.of("type", "array", "items", Map.of(
                                "type", "integer", "minimum", 1), "minItems", 1)),
                "required", List.of("type", "normalizedValue", "sourceTurnVersions"),
                "additionalProperties", false);
        Map<String, Object> properties = Map.of(
                "goals", stringArray(), "confirmedDecisions", stringArray(),
                "constraints", stringArray(), "unresolvedQuestions", stringArray(),
                "keyEntities", Map.of("type", "array", "items", entity));
        JsonSchema schema = JsonSchema.builder().name("conversation_summary")
                .description("单会话结构化滚动摘要")
                .schema(Map.of("type", "object", "properties", properties,
                        "required", List.of("goals", "confirmedDecisions", "constraints",
                                "unresolvedQuestions", "keyEntities"),
                        "additionalProperties", false))
                .strict(true).build();
        return GenerateOptions.builder().temperature(0.0).maxTokens(maxTokens)
                .additionalBodyParam("enable_thinking", false)
                .responseFormat(ResponseFormat.jsonSchema(schema)).build();
    }

    /** 返回允许为空的非空字符串数组 JSON Schema。 */
    private Map<String, Object> stringArray() {
        return Map.of("type", "array", "items", Map.of("type", "string", "minLength", 1));
    }

    /**
     * @param inputTokens 供应商报告的输入 Token
     * @param outputTokens 供应商报告的输出 Token
     * @param inputCharacters 输入字符数
     * @param outputCharacters 输出字符数
     * @param durationMs 完整摘要调用耗时
     * @param succeeded 是否成功得到合法结构化摘要
     */
    public record SummaryUsage(long inputTokens, long outputTokens, long inputCharacters,
                               long outputCharacters, long durationMs, boolean succeeded) { }
}
