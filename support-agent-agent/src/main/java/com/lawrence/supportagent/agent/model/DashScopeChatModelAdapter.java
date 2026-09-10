package com.lawrence.supportagent.agent.model;

import com.lawrence.supportagent.model.ChatModelPort;
import com.lawrence.supportagent.model.ModelInvocationException;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort.ModelOperation;
import com.lawrence.supportagent.retrieval.RetrievalEvidence;
import com.lawrence.supportagent.ticket.TicketDetails;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.formatter.JsonSchema;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 使用 AgentScope DashScope 模型实现内部流式 Chat 和受控工单 Agent。 */
public class DashScopeChatModelAdapter implements ChatModelPort {
    private static final int TICKET_AGENT_MAX_ITERATIONS = 2;
    private final Model model;
    private final PromptTemplate greeting = new PromptTemplate("/prompts/greeting.md");
    private final PromptTemplate grounded;
    private final PromptTemplate ticketPrompt = new PromptTemplate("/prompts/ticket-agent.md");
    private final PromptTemplate ticketDraft = new PromptTemplate("/prompts/ticket-draft.md");
    private final PromptTemplate resolvedCase = new PromptTemplate("/prompts/resolved-case-generation.md");
    private final ObjectMapper objectMapper;
    private final OptimizationTelemetryPort telemetry;
    private final ModelGenerationSettings settings;

    /** 使用显式 API Key、模型和 Base URL 创建统一 Chat 模型。 */
    public DashScopeChatModelAdapter(String apiKey, String modelName, String baseUrl) {
        this(apiKey, modelName, baseUrl, new ObjectMapper(), OptimizationTelemetryPort.noOp(),
                ModelGenerationSettings.stageEightDefaults());
    }

    /** 使用显式 JSON 编解码器创建支持严格结构化输出的 Chat 模型。 */
    public DashScopeChatModelAdapter(String apiKey, String modelName, String baseUrl,
                                     ObjectMapper objectMapper) {
        this(apiKey, modelName, baseUrl, objectMapper, OptimizationTelemetryPort.noOp(),
                ModelGenerationSettings.stageEightDefaults());
    }

    /** 使用显式 JSON 编解码器和低基数遥测创建 Chat 模型。 */
    public DashScopeChatModelAdapter(String apiKey, String modelName, String baseUrl,
                                     ObjectMapper objectMapper,
                                     OptimizationTelemetryPort telemetry) {
        this(apiKey, modelName, baseUrl, objectMapper, telemetry,
                ModelGenerationSettings.stageEightDefaults());
    }

    /** 使用显式生成参数创建 Chat 模型，保证超时和输出上限可配置。 */
    public DashScopeChatModelAdapter(String apiKey, String modelName, String baseUrl,
                                     ObjectMapper objectMapper,
                                     OptimizationTelemetryPort telemetry,
                                     ModelGenerationSettings settings) {
        this(apiKey, modelName, baseUrl, objectMapper, telemetry, settings,
                GroundedPromptVariant.ORIGINAL);
    }

    /** 使用显式生成参数和知识回答 Prompt 版本创建 Chat 模型。 */
    public DashScopeChatModelAdapter(String apiKey, String modelName, String baseUrl,
                                     ObjectMapper objectMapper,
                                     OptimizationTelemetryPort telemetry,
                                     ModelGenerationSettings settings,
                                     GroundedPromptVariant groundedPromptVariant) {
        this.settings = settings;
        this.grounded = new PromptTemplate(groundedPromptVariant.resource());
        this.model = OpenAIChatModel.builder().apiKey(apiKey).modelName(modelName)
                .baseUrl(AgentScopeDashScopeBaseUrl.openAiCompatible(baseUrl)).stream(true)
                .generateOptions(options(settings.chatMaxOutputTokens())).build();
        this.objectMapper = objectMapper;
        this.telemetry = telemetry == null ? OptimizationTelemetryPort.noOp() : telemetry;
    }

    /** {@inheritDoc} */
    @Override
    public ModelAnswer greeting(String message, List<String> recentTurns, Runnable firstTokenCallback) {
        return generate(greeting.render(Map.of()), message, recentTurns,
                firstTokenCallback, greeting.version());
    }

    /** {@inheritDoc} */
    @Override
    public ModelAnswer groundedAnswer(String message, List<String> recentTurns,
                                      List<RetrievalEvidence> evidence, String validationFeedback,
                                      Runnable firstTokenCallback) {
        String sources = evidence.stream().map(item -> "[S" + (evidence.indexOf(item) + 1)
                + "] 来源类型=" + item.sourceType() + "；标题="
                + item.title() + "\n" + item.content()).reduce("", (left, right) -> left + "\n" + right);
        String feedback = validationFeedback == null ? "" : "上一次答案违反规则：" + validationFeedback + "。请完整重写。";
        return generate(grounded.render(Map.of("VALIDATION_FEEDBACK", feedback)),
                "用户问题：" + message + "\n证据：" + sources, recentTurns,
                firstTokenCallback, grounded.version());
    }

    /** {@inheritDoc} */
    @Override
    public ModelAnswer ticketAnswer(String message, String allowedTicketNo, TicketDetails ticket,
                                    List<String> recentTurns, Runnable firstTokenCallback) {
        TicketLookupTool tool = new TicketLookupTool(allowedTicketNo, ticket);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);
        ReActAgent agent = ReActAgent.builder().name("ticket-reader").model(model).toolkit(toolkit)
                .maxIters(TICKET_AGENT_MAX_ITERATIONS)
                .generateOptions(options(settings.chatMaxOutputTokens()))
                .sysPrompt(ticketPrompt.render(Map.of("TICKET_NO", allowedTicketNo)))
                .build();
        try (agent) {
            Msg response = agent.call("用户问题：" + message + "\n请查询：" + allowedTicketNo)
                    .block(settings.chatTimeout());
            if (response == null || response.getTextContent() == null || response.getTextContent().isBlank()
                    || tool.calls() != 1) {
                throw unavailable(null);
            }
            firstTokenCallback.run();
            recordChatUsage(response.getUsage(), message.length(), response.getTextContent().length());
            return new ModelAnswer(response.getTextContent(), ticketPrompt.version(), agent.getAgentState().toJson());
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    /** {@inheritDoc} */
    @Override
    public TicketDraft generateTicketDraft(String frozenContext) {
        ModelAnswer answer = generate(ticketDraft.render(Map.of()), frozenContext, List.of(),
                () -> { }, ticketDraft.version(), settings.structuredMaxOutputTokens(),
                settings.ticketTimeout());
        String[] lines = answer.text().split("\\R", 3);
        if (lines.length != 3) throw unavailable(null);
        return new TicketDraft(value(lines[0]), value(lines[1]), value(lines[2]));
    }

    /** {@inheritDoc} */
    @Override
    public ResolvedCaseDraft generateResolvedCaseDraft(String ticketFacts) {
        Map<String, Object> properties = Map.of(
                "title", Map.of("type", "string", "minLength", 1, "maxLength", 160),
                "problem", Map.of("type", "string", "minLength", 1, "maxLength", 4000));
        Map<String, Object> schema = Map.of("type", "object", "properties", properties,
                "required", List.of("title", "problem"), "additionalProperties", false);
        JsonSchema jsonSchema = JsonSchema.builder().name("resolved_case_draft")
                .description("仅包含案例标题和问题描述的草稿")
                .schema(schema).strict(true).build();
        String raw = generateRaw(resolvedCase.render(Map.of()), ticketFacts,
                GenerateOptions.builder().temperature(0.0)
                        .maxTokens(settings.structuredMaxOutputTokens())
                        .additionalBodyParam("enable_thinking", false)
                        .responseFormat(ResponseFormat.jsonSchema(jsonSchema)).build());
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (!node.isObject() || node.size() != 2 || !node.has("title") || !node.has("problem")) {
                throw new IllegalArgumentException("案例结构化字段不完整");
            }
            return new ResolvedCaseDraft(node.path("title").asText(), node.path("problem").asText());
        } catch (RuntimeException exception) {
            throw new ModelInvocationException("CASE_GENERATION_SCHEMA_INVALID",
                    "案例结构化结果不合法", true, exception);
        }
    }

    /** 内部消费原始流，只在完整响应生成后返回应用层。 */
    private ModelAnswer generate(String system, String user, List<String> recentTurns,
                                 Runnable firstTokenCallback, String promptVersion) {
        return generate(system, user, recentTurns, firstTokenCallback, promptVersion,
                settings.chatMaxOutputTokens(), settings.chatTimeout());
    }

    /** 使用指定输出上限和超时完整消费模型流。 */
    private ModelAnswer generate(String system, String user, List<String> recentTurns,
                                 Runnable firstTokenCallback, String promptVersion,
                                 int maximumOutputTokens, Duration timeout) {
        AtomicBoolean first = new AtomicBoolean();
        AtomicReference<ChatUsage> usage = new AtomicReference<>();
        String history = recentTurns.isEmpty() ? "" : "历史上下文：\n" + String.join("\n", recentTurns) + "\n";
        List<Msg> messages = List.of(Msg.builder().role(MsgRole.SYSTEM).textContent(system).build(),
                Msg.builder().role(MsgRole.USER).textContent(history + user).build());
        StringBuilder complete = new StringBuilder();
        try {
            model.stream(messages, List.of(), options(maximumOutputTokens)).doOnNext(response -> {
                if (response.getUsage() != null) usage.set(response.getUsage());
                for (TextBlock block : response.getContent().stream()
                        .filter(TextBlock.class::isInstance).map(TextBlock.class::cast).toList()) {
                    if (block.getText() != null && !block.getText().isEmpty()) {
                        if (first.compareAndSet(false, true)) firstTokenCallback.run();
                        complete.append(block.getText());
                    }
                }
            }).blockLast(timeout);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        if (complete.isEmpty()) throw unavailable(null);
        recordChatUsage(usage.get(), system.length() + history.length() + user.length(), complete.length());
        return new ModelAnswer(complete.toString(), promptVersion);
    }

    /** 使用指定生成选项完整消费模型流并返回原始结构化正文。 */
    private String generateRaw(String system, String user, GenerateOptions options) {
        List<Msg> messages = List.of(Msg.builder().role(MsgRole.SYSTEM)
                        .textContent(system).build(),
                Msg.builder().role(MsgRole.USER).textContent(user).build());
        StringBuilder complete = new StringBuilder();
        AtomicReference<ChatUsage> usage = new AtomicReference<>();
        try {
            model.stream(messages, List.of(), options).doOnNext(response -> {
                if (response.getUsage() != null) usage.set(response.getUsage());
                for (TextBlock block : response.getContent().stream()
                        .filter(TextBlock.class::isInstance).map(TextBlock.class::cast).toList()) {
                    if (block.getText() != null) complete.append(block.getText());
                }
            }).blockLast(settings.resolvedCaseTimeout());
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        if (complete.isEmpty()) throw unavailable(null);
        recordChatUsage(usage.get(), system.length() + user.length(), complete.length());
        return complete.toString();
    }

    /** 去除工单草稿固定字段名。 */
    private String value(String line) { return line.replaceFirst("^[^：:]+[：:]\\s*", "").trim(); }

    /** 创建关闭思考模式且带显式输出上限的通用生成参数。 */
    private GenerateOptions options(int maximumOutputTokens) {
        return GenerateOptions.builder().temperature(0.0).maxTokens(maximumOutputTokens)
                .additionalBodyParam("enable_thinking", false).build();
    }

    /** 记录 Chat Token 与字符聚合量，不保存任何正文。 */
    private void recordChatUsage(ChatUsage usage, long inputCharacters, long outputCharacters) {
        long inputTokens = usage == null ? 0 : usage.getInputTokens();
        long outputTokens = usage == null ? 0 : usage.getOutputTokens();
        telemetry.recordUsage(ModelOperation.CHAT, inputTokens, outputTokens, 1,
                inputCharacters + outputCharacters);
    }

    /** 创建不泄露供应商正文的统一模型异常。 */
    private ModelInvocationException unavailable(Throwable cause) {
        return new ModelInvocationException("CHAT_MODEL_UNAVAILABLE", "Chat 模型暂时不可用", true, cause);
    }

    /** 只允许读取构造时绑定的单个工单公开视图。 */
    private static final class TicketLookupTool {
        private final String allowedTicketNo;
        private final TicketDetails ticket;
        private final AtomicInteger calls = new AtomicInteger();

        /** 绑定当前路由已由应用层验证的唯一工单。 */
        private TicketLookupTool(String allowedTicketNo, TicketDetails ticket) {
            this.allowedTicketNo = allowedTicketNo; this.ticket = ticket;
        }

        /** 返回指定工单的公开字段，并拒绝第二次或越权查询。 */
        @Tool(name = "get_ticket", description = "按已授权工单编号读取公开工单字段", readOnly = true)
        public String getTicket(@ToolParam(name = "ticketNo", description = "T 加 12 位数字的已授权工单编号")
                                String ticketNo) {
            int attempt = calls.incrementAndGet();
            if (attempt != 1 || !allowedTicketNo.equals(ticketNo)) {
                throw new IllegalArgumentException("不允许读取该工单或重复调用工具");
            }
            return "工单编号：" + ticket.ticketNo() + "\n标题：" + ticket.title()
                    + "\n问题描述：" + ticket.problemDescription()
                    + "\n已尝试操作：" + ticket.attemptedActions() + "\n状态：" + ticket.status()
                    + "\n根因：" + ticket.rootCause() + "\n解决方案：" + ticket.solution()
                    + "\n关闭原因：" + ticket.closeReason();
        }

        /** 返回本次 Agent 实际工具调用次数。 */
        private int calls() { return calls.get(); }
    }
}
