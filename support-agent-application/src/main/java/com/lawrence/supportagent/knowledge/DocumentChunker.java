package com.lawrence.supportagent.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 按 Markdown 标题或 TXT 段落确定性生成长度受控的知识分块。 */
public class DocumentChunker {
    /** 当前确定性分块策略版本。 */
    public static final String VERSION = "chunk-v1";
    private static final int TARGET_LENGTH = 800;
    private static final int MAX_LENGTH = 1200;
    private static final int OVERLAP_LENGTH = 120;
    private static final int MIN_FRAGMENT_LENGTH = 80;
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})[ \\t]+(.+?)\\s*$");
    private final ExactTermExtractor exactTermExtractor;

    /** 注入确定性精确词提取器。 */
    public DocumentChunker(ExactTermExtractor exactTermExtractor) {
        this.exactTermExtractor = exactTermExtractor;
    }

    /** 根据输入类型切分正文，并赋予从零开始的稳定序号。 */
    public List<KnowledgeChunkDraft> chunk(String title, DocumentInputType inputType,
                                           String normalizedContent) {
        List<Section> sections = inputType == DocumentInputType.MARKDOWN_FILE
                ? markdownSections(title, normalizedContent)
                : List.of(new Section(title, normalizedContent));
        List<ChunkText> texts = new ArrayList<>();
        for (Section section : sections) {
            texts.addAll(pack(section.headingPath, semanticUnits(section.content)));
        }
        List<KnowledgeChunkDraft> result = new ArrayList<>();
        for (int index = 0; index < texts.size(); index++) {
            ChunkText value = texts.get(index);
            result.add(new KnowledgeChunkDraft(index, value.headingPath, value.content,
                    sha256(value.content), exactTermExtractor.extract(value.content)));
        }
        return List.copyOf(result);
    }

    /** 按 Markdown ATX 标题维护层级路径并保留标题行正文。 */
    private List<Section> markdownSections(String title, String content) {
        List<Section> sections = new ArrayList<>();
        String[] headings = new String[6];
        StringBuilder body = new StringBuilder();
        String currentPath = title;
        for (String line : content.split("\\n", -1)) {
            Matcher matcher = HEADING.matcher(line);
            if (matcher.matches()) {
                addSection(sections, currentPath, body);
                int level = matcher.group(1).length();
                headings[level - 1] = matcher.group(2).strip();
                Arrays.fill(headings, level, headings.length, null);
                currentPath = headingPath(title, headings);
            }
            if (!body.isEmpty()) {
                body.append('\n');
            }
            body.append(line);
        }
        addSection(sections, currentPath, body);
        return sections;
    }

    /** 追加一个非空标题区段并清空复用缓冲区。 */
    private void addSection(List<Section> sections, String headingPath, StringBuilder body) {
        String value = body.toString().strip();
        if (!value.isEmpty()) {
            sections.add(new Section(headingPath, value));
        }
        body.setLength(0);
    }

    /** 由文档标题和当前 Markdown 标题栈生成稳定路径。 */
    private String headingPath(String title, String[] headings) {
        List<String> values = new ArrayList<>();
        values.add(title);
        for (String heading : headings) {
            if (heading != null && !heading.isBlank()) {
                values.add(heading);
            }
        }
        return String.join(" > ", values);
    }

    /** 按空行切分语义单元，同时保持围栏代码块中的空行完整。 */
    private List<String> semanticUnits(String content) {
        List<String> units = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean fenced = false;
        for (String line : content.split("\\n", -1)) {
            if (line.stripLeading().startsWith("```")) {
                fenced = !fenced;
            }
            if (!fenced && line.isBlank()) {
                appendUnit(units, current);
            } else {
                if (!current.isEmpty()) {
                    current.append('\n');
                }
                current.append(line);
            }
        }
        appendUnit(units, current);
        return units;
    }

    /** 追加非空语义单元并清空缓冲区。 */
    private void appendUnit(List<String> units, StringBuilder current) {
        String value = current.toString().strip();
        if (!value.isEmpty()) {
            units.add(value);
        }
        current.setLength(0);
    }

    /** 把语义单元合并到目标长度，并仅在强制切分时产生重叠。 */
    private List<ChunkText> pack(String headingPath, List<String> units) {
        List<ChunkText> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : units) {
            if (codePointLength(unit) > MAX_LENGTH) {
                flush(result, headingPath, current);
                splitLongUnit(result, headingPath, unit);
                continue;
            }
            String candidate = current.isEmpty() ? unit : current + "\n\n" + unit;
            if (!current.isEmpty() && (codePointLength(candidate) > MAX_LENGTH
                    || codePointLength(current.toString()) >= TARGET_LENGTH)) {
                flush(result, headingPath, current);
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(unit);
        }
        flush(result, headingPath, current);
        mergeSmallTail(result);
        return result;
    }

    /** 将一个超长语义单元按 1200 字符窗口和 120 字符重叠强制切分。 */
    private void splitLongUnit(List<ChunkText> result, String headingPath, String unit) {
        int length = codePointLength(unit);
        int start = 0;
        while (start < length) {
            int end = Math.min(length, start + MAX_LENGTH);
            result.add(new ChunkText(headingPath, substringByCodePoints(unit, start, end).strip()));
            if (end == length) {
                break;
            }
            start = end - OVERLAP_LENGTH;
        }
    }

    /** 追加一个非空分块并清空当前缓冲区。 */
    private void flush(List<ChunkText> result, String headingPath, StringBuilder current) {
        String value = current.toString().strip();
        if (!value.isEmpty()) {
            result.add(new ChunkText(headingPath, value));
        }
        current.setLength(0);
    }

    /** 在不超过最大长度时把不足八十字符的尾块合并进前一块。 */
    private void mergeSmallTail(List<ChunkText> result) {
        if (result.size() < 2) {
            return;
        }
        ChunkText tail = result.get(result.size() - 1);
        ChunkText previous = result.get(result.size() - 2);
        String combined = previous.content + "\n\n" + tail.content;
        if (codePointLength(tail.content) < MIN_FRAGMENT_LENGTH
                && codePointLength(combined) <= MAX_LENGTH
                && previous.headingPath.equals(tail.headingPath)) {
            result.set(result.size() - 2, new ChunkText(previous.headingPath, combined));
            result.remove(result.size() - 1);
        }
    }

    /** 返回字符串的 Unicode 码点数量。 */
    private int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    /** 按 Unicode 码点边界截取字符串，避免拆开代理字符对。 */
    private String substringByCodePoints(String value, int start, int end) {
        int startOffset = value.offsetByCodePoints(0, start);
        int endOffset = value.offsetByCodePoints(0, end);
        return value.substring(startOffset, endOffset);
    }

    /** 计算单个分块正文的小写十六进制 SHA-256。 */
    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }

    /** 保存一个标题上下文中的原始正文。 */
    private record Section(String headingPath, String content) {
    }

    /** 保存准备赋予序号和哈希的分块正文。 */
    private record ChunkText(String headingPath, String content) {
    }
}
