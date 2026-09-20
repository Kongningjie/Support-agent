package com.lawrence.supportagent.memory;

/** 用户本人可见的长期记忆开关和并发版本。 */
public record MemorySettingsView(boolean enabled, long version) {
    /** 拒绝负数版本。 */
    public MemorySettingsView {
        if (version < 0) {
            throw new IllegalArgumentException("记忆设置版本不能为负数");
        }
    }
}
