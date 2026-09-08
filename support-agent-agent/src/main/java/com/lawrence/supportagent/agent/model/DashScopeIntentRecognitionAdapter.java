package com.lawrence.supportagent.agent.model;

import com.lawrence.supportagent.chat.ChatIntent;
import com.lawrence.supportagent.chat.IntentDecision;
import com.lawrence.supportagent.model.IntentRecognitionPort;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 使用独立结构化提示识别意图，任何解析异常由应用层保守降级。 */
public class DashScopeIntentRecognitionAdapter implements IntentRecognitionPort {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern OUTPUT = Pattern.compile(
            "(?s).*INTENT=(GREETING|SUPPORT_QUERY|TICKET_QUERY|OUT_OF_SCOPE)\\s+CONFIDENCE=([01](?:\\.\\d+)?)\\s+QUERY=(.*?)\\s+TICKET=(T\\d{12}|NONE)\\s*$");
    private final Model model;
    private final PromptTemplate prompt = new PromptTemplate("/prompts/intent.md");

    /** 使用与回答适配器隔离的模型实例和显式 Base URL。 */
    public DashScopeIntentRecognitionAdapter(String apiKey, String modelName, String baseUrl) {
        this.model = DashScopeChatModel.builder().apiKey(apiKey).modelName(modelName)
                .baseUrl(baseUrl).stream(false).enableThinking(false).build();
    }

    /** {@inheritDoc} */
    @Override
    public IntentDecision recognize(String message, List<String> recentTurns) {
        String rendered = prompt.render(Map.of("HISTORY", String.join("\n", recentTurns), "MESSAGE", message));
        Msg request = Msg.builder().role(MsgRole.USER).textContent(rendered).build();
        StringBuilder output = new StringBuilder();
        model.stream(List.of(request), List.of(), GenerateOptions.builder().build()).doOnNext(response ->
                response.getContent().stream().filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast).map(TextBlock::getText)
                        .filter(text -> text != null && !text.isEmpty()).forEach(output::append))
                .blockLast(TIMEOUT);
        Matcher matcher = OUTPUT.matcher(output.toString().trim());
        if (!matcher.matches()) throw new IllegalStateException("意图模型输出结构无效");
        String ticket = "NONE".equals(matcher.group(4)) ? null : matcher.group(4);
        return new IntentDecision(ChatIntent.valueOf(matcher.group(1)), Double.parseDouble(matcher.group(2)),
                matcher.group(3).trim(), ticket, "MODEL");
    }
}
