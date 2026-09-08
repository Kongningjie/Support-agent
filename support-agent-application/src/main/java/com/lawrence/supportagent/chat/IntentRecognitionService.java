package com.lawrence.supportagent.chat;

import com.lawrence.supportagent.model.IntentRecognitionPort;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 先执行保守确定性规则，再调用独立意图模型并落实低置信度降级。 */
public class IntentRecognitionService {
    private static final int MAXIMUM_MESSAGE_CHARACTERS = 4_000;
    private static final Pattern TICKET = Pattern.compile("T\\d{12}");
    private static final Pattern TECHNICAL = Pattern.compile(
            "(?i)(报错|异常|失败|怎么|如何|配置|命令|接口|连接|数据库|mysql|redis|elastic|java|spring|error|exception)");
    private static final Set<String> GREETINGS = Set.of("你好", "您好", "在吗", "早上好", "下午好", "晚上好", "谢谢", "hello", "hi");
    private static final Pattern OUT_OF_SCOPE = Pattern.compile(
            "(?i)(天气|股票|基金|彩票|星座|菜谱|写诗|小说|旅游攻略|娱乐新闻)");
    private final IntentRecognitionPort model;
    private final double minimumConfidence;

    /** 创建具有明确模型置信度门槛的意图服务。 */
    public IntentRecognitionService(IntentRecognitionPort model, double minimumConfidence) {
        this.model = model;
        this.minimumConfidence = minimumConfidence;
    }

    /** 返回规则优先且永远具有安全检索问题的最终意图。 */
    public IntentDecision recognize(String message, List<String> recentTurns) {
        String normalized = requireMessage(message);
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (GREETINGS.contains(lower)) {
            return new IntentDecision(ChatIntent.GREETING, 1, normalized, null, "RULE_GREETING");
        }
        Matcher ticket = TICKET.matcher(normalized);
        if (ticket.find() && !TECHNICAL.matcher(normalized).find()) {
            return new IntentDecision(ChatIntent.TICKET_QUERY, 1, normalized,
                    ticket.group(), "RULE_TICKET_NO");
        }
        if (OUT_OF_SCOPE.matcher(normalized).find() && !TECHNICAL.matcher(normalized).find()) {
            return new IntentDecision(ChatIntent.OUT_OF_SCOPE, 1, normalized, null,
                    "RULE_OUT_OF_SCOPE");
        }
        try {
            IntentDecision decision = model.recognize(normalized,
                    recentTurns == null ? List.of() : List.copyOf(recentTurns));
            if (decision == null || decision.intent() == null
                    || !Double.isFinite(decision.confidence())
                    || decision.confidence() < minimumConfidence) {
                return fallback(normalized, "LOW_CONFIDENCE_FALLBACK");
            }
            if (decision.intent() == ChatIntent.TICKET_QUERY
                    && (decision.ticketNo() == null || !TICKET.matcher(decision.ticketNo()).matches())) {
                return new IntentDecision(ChatIntent.TICKET_QUERY, decision.confidence(), normalized,
                        null, "MODEL_TICKET_NUMBER_REQUIRED");
            }
            String query = decision.standaloneQuery() == null
                    || decision.standaloneQuery().isBlank() ? normalized : decision.standaloneQuery().trim();
            return new IntentDecision(decision.intent(), decision.confidence(), query,
                    decision.ticketNo(), "MODEL_CLASSIFIED");
        } catch (RuntimeException exception) {
            return fallback(normalized, "MODEL_FAILURE_FALLBACK");
        }
    }

    /** 校验消息长度并去除首尾空白。 */
    private String requireMessage(String message) {
        if (message == null || message.isBlank()
                || message.trim().length() > MAXIMUM_MESSAGE_CHARACTERS) {
            throw new IllegalArgumentException("用户消息去除首尾空白后必须为 1 至 4000 个字符");
        }
        return message.trim();
    }

    /** 创建模型失败时不会拒绝技术支持请求的保守结果。 */
    private IntentDecision fallback(String message, String reasonCode) {
        return new IntentDecision(ChatIntent.SUPPORT_QUERY, 0, message, null, reasonCode);
    }
}
