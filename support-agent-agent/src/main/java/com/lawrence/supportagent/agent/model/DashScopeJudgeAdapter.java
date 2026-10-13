package com.lawrence.supportagent.agent.model;

import com.lawrence.supportagent.evaluation.JudgeModelPort;
import com.lawrence.supportagent.evaluation.HumanEvaluationRepository;
import com.lawrence.supportagent.chat.ConservativeTokenEstimator;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 独立 DashScope Judge；严格解析结构化评审，不保留原始模型响应。 */
public class DashScopeJudgeAdapter implements JudgeModelPort {
    private final String name;
    private final Model model;
    private final ObjectMapper json = tools.jackson.databind.json.JsonMapper.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final PromptTemplate prompt = new PromptTemplate("/prompts/judge.md");

    /** 独立构造评审模型，不继承 Chat 模型或人工评分。 */
    public DashScopeJudgeAdapter(String key, String name, String baseUrl) {
        this.name = name;
        model = OpenAIChatModel.builder().apiKey(key).modelName(name)
                .baseUrl(AgentScopeDashScopeBaseUrl.openAiCompatible(baseUrl)).stream(false)
                .generateOptions(options()).build();
    }
    /** {@inheritDoc} */
    @Override public String modelName() { return name; }
    /** 返回实际模板哈希与结构契约版本。 */
    @Override public String promptVersion() { return prompt.version() + ":schema-v1"; }
    /** {@inheritDoc} */
    @Override public Result evaluate(HumanEvaluationRepository.Sample sample) {
        String rendered = prompt.render(Map.of("QUESTION", PromptDataBoundary.wrap("question", sample.question()),
                "ANSWER", PromptDataBoundary.wrap("answer", sample.answer()),
                "EVIDENCE", PromptDataBoundary.wrap("evidence", sample.evidence())));
        if (new ConservativeTokenEstimator().estimate(rendered) > 8000) {
            throw new IllegalArgumentException("Judge 输入超出预算");
        }
        StringBuilder output = new StringBuilder();
        AtomicReference<ChatUsage> usage = new AtomicReference<>();
        model.stream(List.of(Msg.builder().role(MsgRole.USER).textContent(rendered).build()), List.of(), options())
                .doOnNext(response -> {
                    if (response.getUsage() != null) usage.set(response.getUsage());
                    response.getContent().stream().filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                            .map(TextBlock::getText).filter(text -> text != null).forEach(output::append);
                    if (output.length() > 20000) throw new IllegalArgumentException("Judge 输出过长");
                }).blockLast(Duration.ofSeconds(60));
        var value = parse(output.toString());
        return new Result(value, usage.get() == null ? null : Long.valueOf(usage.get().getInputTokens()),
                usage.get() == null ? null : Long.valueOf(usage.get().getOutputTokens()));
    }
    /** 严格检查字段和整数类型；不接受代码围栏或额外维度。 */
    public List<Dimension> parse(String value) {
        JsonNode root;
        try { root = json.readTree(value); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("Judge JSON 无效"); }
        if (root == null || !root.isObject() || root.size() != 5) throw new IllegalArgumentException("Judge 五维无效");
        List<Dimension> result = new ArrayList<>();
        for (String key : List.of("factual", "citation", "completeness", "actionability", "clarity")) {
            JsonNode item = root.get(key);
            if (item == null || !item.isObject() || item.size() != 4 || !item.path("score").isIntegralNumber()
                    || !item.path("score").canConvertToInt() || !item.path("reason").isString()
                    || !item.has("evidenceStart") || !item.has("evidenceEnd")) {
                throw new IllegalArgumentException("Judge 维度字段无效");
            }
            result.add(new Dimension(item.get("score").asInt(), item.get("reason").asString(),
                    position(item.get("evidenceStart")), position(item.get("evidenceEnd"))));
        }
        return List.copyOf(result);
    }
    /** 证据位置只接受空值或可表示的整数。 */
    private Integer position(JsonNode value) {
        if (value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException("证据位置必须是整数");
        return value.asInt();
    }
    /** 冻结评审输出上限并关闭供应商思考模式。 */
    private GenerateOptions options() {
        return GenerateOptions.builder().maxTokens(1500).additionalBodyParam("enable_thinking", false)
                .executionConfig(io.agentscope.core.model.ExecutionConfig.builder()
                        .timeout(Duration.ofSeconds(60)).maxAttempts(1).build()).build();
    }
}
