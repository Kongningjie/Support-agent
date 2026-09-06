package com.lawrence.supportagent.idempotency;

import java.time.Duration;

/**
 * 描述一次需要外部幂等保护的写操作。
 *
 * @param operatorId 服务端可信操作者标识
 * @param operationType 稳定业务操作类型
 * @param idempotencyKey 客户端幂等键
 * @param requestHash 规范化请求的 SHA-256
 * @param leaseDuration 执行租约时长
 * @param retentionDuration 幂等结果保留时长
 */
public record IdempotencyCommand(String operatorId, String operationType,
                                 String idempotencyKey, String requestHash,
                                 Duration leaseDuration, Duration retentionDuration) {
}
