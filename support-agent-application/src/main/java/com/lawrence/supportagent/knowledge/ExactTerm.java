package com.lawrence.supportagent.knowledge;

/** 描述精确技术词及其在当前分块正文中的字符位置。 */
public record ExactTerm(ExactTermType type, String normalizedValue, String displayValue,
                        int sourceOffsetStart, int sourceOffsetEnd) {
}
