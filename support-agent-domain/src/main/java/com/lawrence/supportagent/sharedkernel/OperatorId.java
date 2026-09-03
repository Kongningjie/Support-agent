package com.lawrence.supportagent.sharedkernel;

/** 表示执行审计操作的稳定操作者标识。 */
public record OperatorId(String value) {
    /** 校验操作者标识不能为空。 */
    public OperatorId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("操作者标识不能为空");
        }
    }
}
