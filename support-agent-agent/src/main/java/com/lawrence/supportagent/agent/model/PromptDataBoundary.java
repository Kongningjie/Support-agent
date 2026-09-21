package com.lawrence.supportagent.agent.model;

import java.util.List;
import java.util.regex.Pattern;

/** 将不可信模型输入转义并包装为无法由正文闭合的稳定数据区。 */
final class PromptDataBoundary {
    private static final Pattern SOURCE = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    /** 工具类不允许实例化。 */
    private PromptDataBoundary() { }

    /**
     * 使用稳定来源名包装一段不可信文本。
     *
     * @param source 不包含正文或业务标识的低基数来源名
     * @param content 待包装正文，可为空
     * @return 已完成 XML 元字符转义的数据区
     */
    static String wrap(String source, String content) {
        if (source == null || !SOURCE.matcher(source).matches()) {
            throw new IllegalArgumentException("Prompt 数据区来源名不合法");
        }
        return "<untrusted_data source=\"" + source + "\">\n"
                + escape(content) + "\n</untrusted_data>";
    }

    /** 按稳定序号分别包装多段同类不可信上下文。 */
    static String wrapAll(String source, List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (!result.isEmpty()) {
                result.append("\n");
            }
            result.append(wrap(source + "_" + (index + 1), values.get(index)));
        }
        return result.toString();
    }

    /** 转义能够伪造或闭合 XML 风格数据边界的字符。 */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
