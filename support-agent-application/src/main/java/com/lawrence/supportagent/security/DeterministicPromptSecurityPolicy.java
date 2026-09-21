package com.lawrence.supportagent.security;

import java.text.Normalizer;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** 使用信号组合而非单关键词，对中英文 Prompt 注入执行可复现的本地判定。 */
public class DeterministicPromptSecurityPolicy implements PromptSecurityPolicy {
    private static final Pattern SECURITY_TOPIC = Pattern.compile(
            "prompt\\s*inject|prompt\\s*injection|提示词注入|指令注入|越狱|jailbreak|系统提示词|system\\s*prompt",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DISCUSSION = Pattern.compile(
            "什么是|解释|说明|分析|为什么|如何(?:防御|检测|识别|避免)|防止|预防|研究|测试|示例|教程|what\\s+is|explain|detect|prevent|defen[cs]e|research|example",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OVERRIDE = Pattern.compile(
            "(?:忽略|无视|绕过|覆盖|取消|替换|废除).{0,16}(?:之前|以上|系统|开发者|安全|规则|指令|限制)|"
                    + "(?:ignore|disregard|override|bypass|replace).{0,32}(?:previous|above|system|developer|safety|rule|instruction)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXFILTRATION = Pattern.compile(
            "(?:输出|显示|展示|泄露|打印|返回|告诉).{0,24}(?:系统提示词|隐藏规则|开发者指令|内部指令|内部状态|完整prompt)|"
                    + "(?:reveal|show|print|return|leak|tell).{0,32}(?:system\\s*prompt|hidden\\s*(?:rule|instruction)|developer\\s*message|internal\\s*(?:instruction|state))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ROLE = Pattern.compile(
            "<\\|(?:system|developer|tool|assistant)\\|>|\\[(?:system|developer|tool)]|"
                    + "(?:^|\\R)\\s*(?:system|developer|tool)\\s*:|你现在是(?:系统|开发者)|扮演(?:系统|开发者)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TOOL = Pattern.compile(
            "(?:调用|执行|使用|重复调用).{0,24}(?:未授权|任意|所有|其他)(?:工具|函数|工单)|"
                    + "(?:调用|执行|使用|重复调用).{0,24}get_ticket.{0,24}(?:其他|任意|所有)|"
                    + "(?:call|execute|use).{0,32}(?:unauthorized|arbitrary|other|all).{0,16}(?:tool|function|ticket)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ENCODED = Pattern.compile(
            "(?:base64|rot13|十六进制|hex|编码|解码).{0,32}(?:执行|遵循|照做|指令|execute|follow|instruction)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IMPERATIVE = Pattern.compile(
            "请|立即|现在|必须|务必|直接|执行|输出|显示|展示|告诉|调用|忽略|无视|绕过|"
                    + "please|now|must|execute|output|show|reveal|call|ignore|bypass",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_EXECUTION = Pattern.compile(
            "不要解释|直接执行|照做|按此执行|并执行|实际执行|do\\s+not\\s+explain|execute\\s+it|follow\\s+it",
            Pattern.CASE_INSENSITIVE);
    private final LlmSecuritySettings settings;
    private final LlmSecurityTelemetryPort telemetry;

    /** 注入已经过配置层生产约束校验的阶段 15 安全设置。 */
    public DeterministicPromptSecurityPolicy(LlmSecuritySettings settings) {
        this(settings, LlmSecurityTelemetryPort.noOp());
    }

    /** 注入安全设置和不携带正文的低基数遥测端口。 */
    public DeterministicPromptSecurityPolicy(LlmSecuritySettings settings,
                                             LlmSecurityTelemetryPort telemetry) {
        this.settings = Objects.requireNonNull(settings, "LLM 安全设置不能为空");
        this.telemetry = telemetry == null ? LlmSecurityTelemetryPort.noOp() : telemetry;
    }

    /** {@inheritDoc} */
    @Override
    public PromptSecurityAssessment assess(String content, PromptSecuritySource source) {
        if (!settings.enabled() || content == null || content.isBlank()) {
            PromptSecurityAssessment assessment = new PromptSecurityAssessment(
                    PromptSecurityAction.ALLOW, Set.of(), source);
            telemetry.recordPromptAssessment(source, assessment.action(), assessment.signals());
            return assessment;
        }
        String normalized = Normalizer.normalize(content, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        EnumSet<PromptSecuritySignal> signals = EnumSet.noneOf(PromptSecuritySignal.class);
        addIfMatches(signals, normalized, OVERRIDE, PromptSecuritySignal.INSTRUCTION_OVERRIDE);
        addIfMatches(signals, normalized, EXFILTRATION, PromptSecuritySignal.PROMPT_EXFILTRATION);
        addIfMatches(signals, normalized, ROLE, PromptSecuritySignal.ROLE_IMPERSONATION);
        addIfMatches(signals, normalized, TOOL, PromptSecuritySignal.TOOL_ESCALATION);
        addIfMatches(signals, normalized, ENCODED, PromptSecuritySignal.ENCODED_INSTRUCTION);
        boolean discussion = SECURITY_TOPIC.matcher(normalized).find()
                && DISCUSSION.matcher(normalized).find();
        if (discussion) {
            signals.add(PromptSecuritySignal.SECURITY_DISCUSSION);
        }
        PromptSecurityAction action = action(normalized, signals, discussion);
        if (action == PromptSecurityAction.BLOCK && source == PromptSecuritySource.USER_MESSAGE
                && !settings.blockHighConfidenceInput()) {
            action = PromptSecurityAction.GUARD;
        } else if (action == PromptSecurityAction.BLOCK
                && source != PromptSecuritySource.USER_MESSAGE
                && !settings.excludeHighRiskContext()) {
            action = PromptSecurityAction.GUARD;
        }
        PromptSecurityAssessment assessment = new PromptSecurityAssessment(action, signals, source);
        telemetry.recordPromptAssessment(source, assessment.action(), assessment.signals());
        if (assessment.blocked() && source != PromptSecuritySource.USER_MESSAGE) {
            telemetry.recordContextExclusion(source);
        }
        return assessment;
    }

    /** 仅在模式命中时加入对应低基数信号。 */
    private void addIfMatches(EnumSet<PromptSecuritySignal> signals, String content,
                              Pattern pattern, PromptSecuritySignal signal) {
        if (pattern.matcher(content).find()) {
            signals.add(signal);
        }
    }

    /** 使用多信号组合和安全讨论豁免选择最终动作。 */
    private PromptSecurityAction action(String content, Set<PromptSecuritySignal> signals,
                                        boolean discussion) {
        if (signals.isEmpty()) {
            return PromptSecurityAction.ALLOW;
        }
        boolean imperative = IMPERATIVE.matcher(content).find();
        boolean explicitlyExecute = EXPLICIT_EXECUTION.matcher(content).find();
        if (discussion && !explicitlyExecute) {
            return PromptSecurityAction.GUARD;
        }
        boolean override = signals.contains(PromptSecuritySignal.INSTRUCTION_OVERRIDE);
        boolean exfiltration = signals.contains(PromptSecuritySignal.PROMPT_EXFILTRATION);
        boolean role = signals.contains(PromptSecuritySignal.ROLE_IMPERSONATION);
        boolean tool = signals.contains(PromptSecuritySignal.TOOL_ESCALATION);
        boolean encoded = signals.contains(PromptSecuritySignal.ENCODED_INSTRUCTION);
        boolean blocked = imperative && (override || exfiltration || tool
                || (role && explicitlyExecute) || (encoded && explicitlyExecute));
        return blocked ? PromptSecurityAction.BLOCK : PromptSecurityAction.GUARD;
    }
}
