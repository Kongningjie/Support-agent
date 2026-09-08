package com.lawrence.supportagent.agent.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 加载 UTF-8 Markdown Prompt，并强制变量允许名单与短 SHA-256 版本。 */
final class PromptTemplate {
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([A-Z_]+)}}");
    private final String source;
    private final Set<String> variables;
    private final String version;

    /** 从类路径加载完整模板并预计算变量和版本。 */
    PromptTemplate(String resource) {
        try (InputStream input = PromptTemplate.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Prompt 资源不存在：" + resource);
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Prompt 资源无法读取：" + resource, exception);
        }
        variables = VARIABLE.matcher(source).results().map(result -> result.group(1))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        version = sha256(source).substring(0, 12);
    }

    /** 只接受模板声明的变量并返回完成替换的文本。 */
    String render(Map<String, String> values) {
        if (!values.keySet().equals(variables)) throw new IllegalArgumentException("Prompt 变量与允许名单不一致");
        Matcher matcher = VARIABLE.matcher(source);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** 返回模板正文的十二位 SHA-256 版本。 */
    String version() { return version; }

    /** 计算 UTF-8 SHA-256。 */
    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("JDK 不支持 SHA-256", exception); }
    }
}
