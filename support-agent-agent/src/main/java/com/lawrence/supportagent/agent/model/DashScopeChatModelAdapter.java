package com.lawrence.supportagent.agent.model;

import com.lawrence.supportagent.model.ChatModelPort;
import com.lawrence.supportagent.model.ModelInvocationException;
import com.lawrence.supportagent.retrieval.RetrievalEvidence;
import com.lawrence.supportagent.ticket.TicketDetails;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.formatter.JsonSchema;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 使用 AgentScope DashScope 模型实现内部流式 Chat 和受控工单 Agent。 */
public class DashScopeChatModelAdapter implements ChatModelPort {
    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final int TICKET_AGENT_MAX_ITERATIONS = 2;
    private final Model model;
    private final PromptTemplate greeting = new PromptTemplate("/prompts/greeting.md");
    private final PromptTemplate grounded = new PromptTemplate("/prompts/grounded-answer.md");
    private final PromptTemplate ticketPrompt = new PromptTemplate("/prompts/ticket-agent.md");
    private final PromptTemplate ticketDraft = new PromptTemplate("/prompts/ticket-draft.md");
    private final PromptTemplate resolvedCase = new PromptTemplate("/prompts/resolved-case-generation.md");
    private final ObjectMapper objectMapper;

    /** 使用显式 API Key、模型和 Base URL 创建统一 Chat 模型。 */
    public DashScopeChatModelAdapter(String apiKey, String modelName, String baseUrl) {
        this(apiKey, modelName, baseUrl, new ObjectMapper());
    }

    /** 使用显式 JSON 编解码器创建支持严格结构化输出的 Chat 模型。 */
    public DashScopeChatModelAdapter(String apiKey, String modelName, String baseUrl,
                                     ObjectMapper objectMapper) {
        this.model = DashScopeChatModel.builder().apiKey(apiKey).modelName(modelName)
                .baseUrl(AgentScopeDashScopeBaseUrl.normalize(baseUrl))
                .stream(true).enableThinking(false).build();
        this.objectMapper = objectMapper;
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
                .sysPrompt(ticketPrompt.render(Map.of("TICKET_NO", allowedTicketNo)))
                .build();
        try (agent) {
            firstTokenCallback.run();
            Msg response = agent.call("用户问题：" + message + "\n请查询：" + allowedTicketNo)
                    .block(TIMEOUT);
            if (response == null || response.getTextContent() == null || response.getTextContent().isBlank()
                    || tool.calls() != 1) {
                throw unavailable(null);
            }
            return new ModelAnswer(response.getTextContent(), ticketPrompt.version(), agent.getAgentState().toJson());
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    /** {@inheritDoc} */
    @Override
    public TicketDraft generateTicketDraft(String frozenContext) {
        ModelAnswer answer = generate(ticketDraft.render(Map.of()), frozenContext, List.of(),
                () -> { }, ticketDraft.version());
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
        AtomicBoolean first = new AtomicBoolean();
        String history = recentTurns.isEmpty() ? "" : "历史上下文：\n" + String.join("\n", recentTurns) + "\n";
        List<Msg> messages = List.of(Msg.builder().role(MsgRole.SYSTEM).textContent(system).build(),
                Msg.builder().role(MsgRole.USER).textContent(history + user).build());
        StringBuilder complete = new StringBuilder();
        try {
            model.stream(messages, List.of(), GenerateOptions.builder().build()).doOnNext(response -> {
                for (TextBlock block : response.getContent().stream()
                        .filter(TextBlock.class::isInstance).map(TextBlock.class::cast).toList()) {
                    if (block.getText() != null && !block.getText().isEmpty()) {
                        if (first.compareAndSet(false, true)) firstTokenCallback.run();
                        complete.append(block.getText());
                    }
                }
            }).blockLast(TIMEOUT);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        if (complete.isEmpty()) throw unavailable(null);
        return new ModelAnswer(complete.toString(), promptVersion);
    }

    /** 使用指定生成选项完整消费模型流并返回原始结构化正文。 */
    private String generateRaw(String system, String user, GenerateOptions options) {
        List<Msg> messages = List.of(Msg.builder().role(MsgRole.SYSTEM)
                        .textContent(system).build(),
                Msg.builder().role(MsgRole.USER).textContent(user).build());
        StringBuilder complete = new StringBuilder();
        try {
            model.stream(messages, List.of(), options).doOnNext(response -> {
                for (TextBlock block : response.getContent().stream()
                        .filter(TextBlock.class::isInstance).map(TextBlock.class::cast).toList()) {
                    if (block.getText() != null) complete.append(block.getText());
                }
            }).blockLast(Duration.ofSeconds(60));
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        if (complete.isEmpty()) throw unavailable(null);
        return complete.toString();
    }

    /** 去除工单草稿固定字段名。 */
    private String value(String line) { return line.replaceFirst("^[^：:]+[：:]\\s*", "").trim(); }

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
