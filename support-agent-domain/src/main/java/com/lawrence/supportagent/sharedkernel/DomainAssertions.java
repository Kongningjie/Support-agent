package com.lawrence.supportagent.sharedkernel;

/** 提供领域对象共享的参数和状态断言。 */
public final class DomainAssertions {
    /** 禁止实例化无状态断言工具。 */
    private DomainAssertions() {
    }

    /** 返回去除首尾空白后的必填文本。 */
    public static String requiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        return value.trim();
    }

    /** 校验版本号不能为负数。 */
    public static long version(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("版本号不能为负数");
        }
        return value;
    }

    /** 校验状态转换的前置状态。 */
    public static void state(boolean valid, String message) {
        if (!valid) {
            throw new IllegalStateException(message);
        }
    }
}
