CREATE TABLE app_user (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'MySQL 内部自增主键，不通过 API 暴露',
    user_id BINARY(16) NOT NULL COMMENT '用户公开 UUID，创建后不可变',
    username VARCHAR(64) NOT NULL COMMENT '规范化为小写的唯一登录用户名',
    display_name VARCHAR(100) NOT NULL COMMENT '用户展示名称，不参与登录',
    password_hash VARCHAR(200) NOT NULL COMMENT 'BCrypt 密码哈希，禁止通过接口或日志暴露',
    role VARCHAR(16) NOT NULL COMMENT '用户角色：USER 或 ADMIN',
    status VARCHAR(16) NOT NULL COMMENT '账号状态：ACTIVE 或 DISABLED',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '账号乐观锁版本',
    password_changed_at DATETIME(6) NOT NULL COMMENT '最近一次设置密码的 UTC 时间',
    created_by VARCHAR(64) NOT NULL COMMENT '创建用户的公开用户 UUID 或 SYSTEM_BOOTSTRAP',
    created_at DATETIME(6) NOT NULL COMMENT '创建 UTC 时间',
    updated_by VARCHAR(64) NOT NULL COMMENT '最近修改用户的公开用户 UUID 或 SYSTEM_BOOTSTRAP',
    updated_at DATETIME(6) NOT NULL COMMENT '最近修改 UTC 时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_user_id (user_id),
    UNIQUE KEY uk_app_user_username (username),
    KEY idx_app_user_status_role (status, role),
    CONSTRAINT chk_app_user_role CHECK (role IN ('USER','ADMIN')),
    CONSTRAINT chk_app_user_status CHECK (status IN ('ACTIVE','DISABLED')),
    CONSTRAINT chk_app_user_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='本地登录用户与两级角色';

ALTER TABLE ticket
    ADD COLUMN owner_user_id BINARY(16) NULL COMMENT '工单所有者公开用户 UUID；空值表示阶段 11 前历史工单' AFTER source_turn_id,
    ADD KEY idx_ticket_owner_created (owner_user_id, created_at, id);

ALTER TABLE agent_run
    ADD COLUMN user_id BINARY(16) NULL COMMENT '发起本次 Agent 运行的公开用户 UUID；空值表示历史运行' AFTER client_message_id,
    ADD KEY idx_agent_run_user_created (user_id, created_at, id);
