package com.lawrence.supportagent.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 按冻结规则从单个分块提取最多一百个可定位精确技术词。 */
public class ExactTermExtractor {
    /** 当前确定性提取规则版本。 */
    public static final String VERSION = "exact-v1";
    private static final int MAX_TERMS = 100;
    private static final Pattern TICKET = Pattern.compile("\\bT\\d{12}\\b");
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>()\\[\\]{}]+|/api/[A-Za-z0-9_./{}:-]+");
    private static final Pattern WINDOWS_PATH = Pattern.compile("(?i)(?:[A-Z]:\\\\|\\\\\\\\)[^\\r\\n\\t<>|\"?*]+");
    private static final Pattern UNIX_PATH = Pattern.compile("(?<![A-Za-z0-9])/(?:[A-Za-z0-9._-]+/)+[A-Za-z0-9._-]+");
    private static final Pattern VERSION_PATTERN = Pattern.compile("(?i)\\bv?\\d+\\.\\d+(?:\\.\\d+){0,2}(?:[-+][A-Za-z0-9.-]+)?\\b");
    private static final Pattern ERROR = Pattern.compile("\\b(?:[A-Z][A-Z0-9]*[-_][A-Z0-9_-]{2,}|[A-Z][A-Za-z0-9]*(?:Exception|Error))\\b");
    private static final Pattern PACKAGE = Pattern.compile("\\b(?:[a-z][A-Za-z0-9_]*\\.){2,}[A-Za-z][A-Za-z0-9_]*\\b");
    private static final Pattern CONFIG = Pattern.compile("\\b[a-z][a-z0-9-]*(?:\\.[a-z0-9-]+){1,}\\b");
    private static final Pattern INLINE_CODE = Pattern.compile("(?<!`)`([^`\\r\\n]{1,1000})`(?!`)");
    private static final Pattern FENCED_COMMAND = Pattern.compile(
            "(?is)```(?:bash|shell|sh|powershell|ps1)\\s*\\n(.*?)\\n```");
    private static final Pattern EXPLICIT_COMMAND_LINE = Pattern.compile(
            "(?im)(?:执行命令|运行以下命令)[：:]?\\s*\\n[ \\t]*([^\\r\\n]{1,1000})");
    private static final Pattern PROMPT_COMMAND = Pattern.compile(
            "(?m)^[ \\t]*(?:PS>|\\$|>)[ \\t]+([^\\r\\n]{1,1000})$");

    /** 提取命令及其余七类技术词，并按类型和值稳定去重。 */
    public List<ExactTerm> extract(String content) {
        Map<String, ExactTerm> terms = new LinkedHashMap<>();
        addMatches(terms, content, FENCED_COMMAND, ExactTermType.COMMAND, 1, true);
        addMatches(terms, content, INLINE_CODE, ExactTermType.COMMAND, 1, true);
        addMatches(terms, content, EXPLICIT_COMMAND_LINE, ExactTermType.COMMAND, 1, true);
        addMatches(terms, content, PROMPT_COMMAND, ExactTermType.COMMAND, 1, true);
        addMatches(terms, content, TICKET, ExactTermType.TICKET_NO, 0, false);
        addMatches(terms, content, URL, ExactTermType.URL_OR_ENDPOINT, 0, false);
        addMatches(terms, content, WINDOWS_PATH, ExactTermType.FILE_PATH, 0, false);
        addMatches(terms, content, UNIX_PATH, ExactTermType.FILE_PATH, 0, false);
        addMatches(terms, content, VERSION_PATTERN, ExactTermType.VERSION, 0, false);
        addMatches(terms, content, ERROR, ExactTermType.ERROR_CODE, 0, false);
        addMatches(terms, content, PACKAGE, ExactTermType.CLASS_OR_PACKAGE, 0, false);
        addMatches(terms, content, CONFIG, ExactTermType.CONFIG_KEY, 0, false);
        return List.copyOf(new ArrayList<>(terms.values()).subList(0,
                Math.min(MAX_TERMS, terms.size())));
    }

    /** 将一种正则匹配追加到保持原文顺序的去重映射。 */
    private void addMatches(Map<String, ExactTerm> target, String content, Pattern pattern,
                            ExactTermType type, int group, boolean command) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find() && target.size() < MAX_TERMS) {
            String display = matcher.group(group).strip();
            if (command) {
                display = stripPrompt(display);
            }
            if (display.isBlank() || display.length() > 1000) {
                continue;
            }
            int start = group == 0 ? matcher.start() : matcher.start(group);
            int end = group == 0 ? matcher.end() : matcher.end(group);
            String normalized = normalize(type, display);
            target.putIfAbsent(type.name() + '\u0000' + normalized,
                    new ExactTerm(type, normalized, display, start, end));
        }
    }

    /** 去除命令提示符但保留参数、引号、路径和原始大小写。 */
    private String stripPrompt(String command) {
        return command.replaceFirst("(?m)^[ \\t]*(?:PS>|\\$|>)[ \\t]+", "").strip();
    }

    /** 按类型生成用于精确匹配的稳定值。 */
    private String normalize(ExactTermType type, String display) {
        return switch (type) {
            case ERROR_CODE, TICKET_NO -> display.toUpperCase(Locale.ROOT);
            case VERSION -> display.toLowerCase(Locale.ROOT).replaceFirst("^v", "");
            default -> display;
        };
    }
}
