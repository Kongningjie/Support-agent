package com.lawrence.supportagent.persistence.record;

import java.time.Instant;

/** MyBatis 使用的外部请求幂等记录，不复用应用层对象。 */
public class IdempotencyRecordDO {
    /** 幂等记录内部自增主键。 */
    public Long id;
    /** 发起操作的服务端可信操作者。 */
    public String operatorId;
    /** 稳定业务操作类型。 */
    public String operationType;
    /** 客户端在该操作类型下提供的幂等键。 */
    public String idempotencyKey;
    /** 规范化请求内容的 SHA-256。 */
    public String requestHash;
    /** PROCESSING、SUCCEEDED、FAILED_RETRYABLE 或 FAILED_FINAL。 */
    public String status;
    /** 成功结果对应的稳定资源类型。 */
    public String resourceType;
    /** 成功结果对应的服务端资源定位 ID。 */
    public Long resourceId;
    /** 成功码或最终失败的稳定错误码。 */
    public String responseCode;
    /** 不包含内部异常信息的失败摘要。 */
    public String failureMessage;
    /** PROCESSING 状态的执行租约截止 UTC 时间。 */
    public Instant lockedUntil;
    /** 记录首次创建 UTC 时间。 */
    public Instant createdAt;
    /** 记录最近更新 UTC 时间。 */
    public Instant updatedAt;
    /** 幂等保证到期 UTC 时间。 */
    public Instant expiresAt;
}
