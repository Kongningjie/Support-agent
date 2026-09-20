package com.lawrence.supportagent.chat;

import java.util.List;

/** 按冻结规则对中英文混合内容执行无外部依赖的保守 Token 估算。 */
public class ConservativeTokenEstimator {
    private static final double SAFETY_MULTIPLIER = 1.15D;

    /** 估算单段文本 Token 数，并对协议和分词误差增加百分之十五余量。 */
    public int estimate(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        int base = 0;
        int nonCjkRun = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (isCjk(codePoint)) {
                base += roundedNonCjk(nonCjkRun) + 1;
                nonCjkRun = 0;
            } else {
                nonCjkRun++;
            }
            offset += Character.charCount(codePoint);
        }
        base += roundedNonCjk(nonCjkRun);
        return (int) Math.ceil(base * SAFETY_MULTIPLIER);
    }

    /** 估算多段上下文的合计 Token 数。 */
    public int estimate(List<String> values) {
        if (values == null) {
            return 0;
        }
        return values.stream().mapToInt(this::estimate).sum();
    }

    /** 判断代码点是否属于常用 CJK、扩展汉字或全角标点区域。 */
    private boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL
                || (codePoint >= 0xFF00 && codePoint <= 0xFFEF);
    }

    /** 将一个连续非 CJK 片段按每四个代码点一个 Token 向上取整。 */
    private int roundedNonCjk(int length) {
        return (length + 3) / 4;
    }
}
