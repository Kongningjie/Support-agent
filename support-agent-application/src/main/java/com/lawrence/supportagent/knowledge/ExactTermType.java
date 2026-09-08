package com.lawrence.supportagent.knowledge;

/** 一期允许进入知识索引的精确技术词类型。 */
public enum ExactTermType {
    /** 产品、协议或异常错误码。 */
    ERROR_CODE,
    /** 产品、组件、协议或运行时版本。 */
    VERSION,
    /** 具有明确执行上下文的命令。 */
    COMMAND,
    /** 配置项名称。 */
    CONFIG_KEY,
    /** Windows、Linux 或配置文件路径。 */
    FILE_PATH,
    /** T 加 12 位数字的工单号。 */
    TICKET_NO,
    /** Java 类名或完整包名。 */
    CLASS_OR_PACKAGE,
    /** 完整 URL 或 API 路径。 */
    URL_OR_ENDPOINT
}
