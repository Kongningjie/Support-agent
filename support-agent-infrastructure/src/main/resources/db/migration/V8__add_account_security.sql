ALTER TABLE app_user
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否必须在下次认证后先修改一次性密码' AFTER password_changed_at,
    ADD COLUMN locked_until DATETIME(6) NULL COMMENT '账号临时锁定截止 UTC 时间；空值表示未锁定' AFTER must_change_password,
    ADD KEY idx_app_user_created (created_at, id),
    ADD KEY idx_app_user_locked_until (locked_until);

CREATE TABLE security_event (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '安全事件内部自增主键',
    event_type VARCHAR(40) NOT NULL COMMENT '稳定安全事件类型',
    target_user_id BINARY(16) NULL COMMENT '目标用户公开 UUID；未知账号登录失败时为空',
    actor_id VARCHAR(64) NOT NULL COMMENT '操作者公开 UUID、ANONYMOUS 或系统身份',
    result VARCHAR(16) NOT NULL COMMENT '事件结果：SUCCEEDED 或 DENIED',
    reason VARCHAR(40) NOT NULL COMMENT '不含正文的低基数原因分类',
    source_hash CHAR(64) NULL COMMENT '客户端来源的 SHA-256；不保存原始地址',
    occurred_at DATETIME(6) NOT NULL COMMENT '事件发生 UTC 时间',
    PRIMARY KEY (id),
    KEY idx_security_event_target_time (target_user_id, occurred_at, id),
    KEY idx_security_event_type_time (event_type, occurred_at, id),
    CONSTRAINT chk_security_event_result CHECK (result IN ('SUCCEEDED','DENIED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不含秘密和请求正文的账号安全事件';
