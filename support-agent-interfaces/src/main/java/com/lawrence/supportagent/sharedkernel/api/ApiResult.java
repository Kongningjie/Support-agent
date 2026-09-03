package com.lawrence.supportagent.sharedkernel.api;

import java.time.Instant;

/**
 * 普通 JSON API 的统一响应结构，SSE 不使用此结构。
 *
 * @param code 稳定业务结果码；成功固定为 SUCCESS
 * @param message 面向调用方的安全结果说明
 * @param data 成功数据；失败时为空
 * @param traceId 服务端生成的请求追踪标识
 * @param timestamp 服务端生成响应的 UTC 时间
 * @param <T> 成功数据类型
 */
public record ApiResult<T>(String code, String message, T data, String traceId, Instant timestamp) {
    /** 创建成功响应。 */
    public static <T> ApiResult<T> success(T data, String traceId, Instant timestamp) {
        return new ApiResult<>("SUCCESS", "成功", data, traceId, timestamp);
    }

    /** 创建不包含敏感内部细节的失败响应。 */
    public static ApiResult<Void> failure(String code, String message, String traceId, Instant timestamp) {
        return new ApiResult<>(code, message, null, traceId, timestamp);
    }
}
