package com.lawrence.supportagent.idempotency;

/**
 * 保存幂等操作首次成功时的公开资源定位信息和当前返回值。
 *
 * @param resourceType 稳定资源类型
 * @param resourceId 资源内部定位 ID，仅用于服务端重查
 * @param value 当前调用应返回的应用层结果
 * @param <T> 应用层结果类型
 */
public record IdempotentResource<T>(String resourceType, long resourceId, T value) {
}
