CREATE TABLE knowledge_space (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'MySQL 内部自增主键，不通过 API 暴露',
    space_id BINARY(16) NOT NULL COMMENT '知识空间公开 UUID，创建后不可变',
    code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '企业内唯一且创建后不可修改的稳定空间代码',
    name VARCHAR(100) NOT NULL COMMENT '用户可见空间名称',
    description VARCHAR(500) NULL COMMENT '空间用途和知识边界说明',
    visibility VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '读取可见性：ENTERPRISE 或 RESTRICTED',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '空间状态：ACTIVE 或 DISABLED',
    system_space BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否为系统内置空间；四期只有 GLOBAL 为 TRUE',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '空间乐观锁版本',
    created_by VARCHAR(64) NOT NULL COMMENT '创建操作者公开用户 UUID 或系统身份',
    created_at DATETIME(6) NOT NULL COMMENT '创建 UTC 时间',
    updated_by VARCHAR(64) NOT NULL COMMENT '最近修改操作者公开用户 UUID 或系统身份',
    updated_at DATETIME(6) NOT NULL COMMENT '最近修改 UTC 时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_space_space_id (space_id),
    UNIQUE KEY uk_knowledge_space_code (code),
    KEY idx_knowledge_space_status_name (status, name, space_id),
    CONSTRAINT chk_knowledge_space_visibility CHECK (visibility IN ('ENTERPRISE','RESTRICTED')),
    CONSTRAINT chk_knowledge_space_status CHECK (status IN ('ACTIVE','DISABLED')),
    CONSTRAINT chk_knowledge_space_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企业内部知识共享与隔离空间';

INSERT INTO knowledge_space
    (space_id,code,name,description,visibility,status,system_space,version,
     created_by,created_at,updated_by,updated_at)
VALUES
    (UNHEX(REPLACE('00000000-0000-0000-0000-000000000001','-','')),
     'GLOBAL','企业公共空间','所有活动用户均可读取的企业公共知识空间',
     'ENTERPRISE','ACTIVE',TRUE,0,'SYSTEM_MIGRATION',UTC_TIMESTAMP(6),
     'SYSTEM_MIGRATION',UTC_TIMESTAMP(6));

CREATE TABLE user_space_membership (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'MySQL 内部自增主键，不通过 API 暴露',
    user_id BINARY(16) NOT NULL COMMENT '成员公开用户 UUID',
    space_id BINARY(16) NOT NULL COMMENT '所属知识空间公开 UUID',
    role VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '空间角色：READER、EDITOR 或 MANAGER',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '成员状态：ACTIVE 或 REVOKED',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '角色变化、恢复和撤销使用的乐观锁版本',
    created_by VARCHAR(64) NOT NULL COMMENT '创建操作者公开用户 UUID 或系统身份',
    created_at DATETIME(6) NOT NULL COMMENT '创建 UTC 时间',
    updated_by VARCHAR(64) NOT NULL COMMENT '最近修改操作者公开用户 UUID 或系统身份',
    updated_at DATETIME(6) NOT NULL COMMENT '最近修改 UTC 时间',
    revoked_by VARCHAR(64) NULL COMMENT '最近撤销操作者公开用户 UUID',
    revoked_at DATETIME(6) NULL COMMENT '最近撤销 UTC 时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_space_membership_user_space (user_id, space_id),
    KEY idx_user_space_membership_space_status_role (space_id, status, role, id),
    KEY idx_user_space_membership_user_status (user_id, status, space_id),
    CONSTRAINT fk_space_membership_user FOREIGN KEY (user_id) REFERENCES app_user(user_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_space_membership_space FOREIGN KEY (space_id) REFERENCES knowledge_space(space_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_space_membership_role CHECK (role IN ('READER','EDITOR','MANAGER')),
    CONSTRAINT chk_space_membership_status CHECK (status IN ('ACTIVE','REVOKED')),
    CONSTRAINT chk_space_membership_version CHECK (version >= 0),
    CONSTRAINT chk_space_membership_revocation CHECK (
        (status='ACTIVE' AND revoked_by IS NULL AND revoked_at IS NULL)
        OR (status='REVOKED' AND revoked_by IS NOT NULL AND revoked_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户与知识空间的可撤销成员关系';

ALTER TABLE managed_document ADD COLUMN space_id BINARY(16) NULL
    COMMENT '文档所属知识空间公开 UUID' AFTER id;
UPDATE managed_document SET space_id=UNHEX(REPLACE('00000000-0000-0000-0000-000000000001','-',''))
WHERE space_id IS NULL;
ALTER TABLE managed_document
    MODIFY COLUMN space_id BINARY(16) NOT NULL COMMENT '文档所属知识空间公开 UUID',
    ADD KEY idx_managed_document_space_status_created (space_id,status,created_at,id),
    ADD CONSTRAINT fk_managed_document_space FOREIGN KEY (space_id) REFERENCES knowledge_space(space_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    DROP INDEX uk_managed_document_active_content_hash,
    ADD UNIQUE KEY uk_managed_document_space_active_hash (space_id,active_content_hash);

ALTER TABLE ticket ADD COLUMN space_id BINARY(16) NULL
    COMMENT '工单问题所属知识空间公开 UUID' AFTER id;
UPDATE ticket SET space_id=UNHEX(REPLACE('00000000-0000-0000-0000-000000000001','-',''))
WHERE space_id IS NULL;
ALTER TABLE ticket
    MODIFY COLUMN space_id BINARY(16) NOT NULL COMMENT '工单问题所属知识空间公开 UUID',
    ADD KEY idx_ticket_space_status_created (space_id,status,created_at,id),
    ADD CONSTRAINT fk_ticket_space FOREIGN KEY (space_id) REFERENCES knowledge_space(space_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE resolved_case ADD COLUMN space_id BINARY(16) NULL
    COMMENT '案例继承的来源工单知识空间公开 UUID' AFTER id;
UPDATE resolved_case resolved
JOIN ticket source ON source.id=resolved.source_ticket_id
SET resolved.space_id=source.space_id
WHERE resolved.space_id IS NULL;
ALTER TABLE resolved_case
    MODIFY COLUMN space_id BINARY(16) NOT NULL COMMENT '案例继承的来源工单知识空间公开 UUID',
    ADD KEY idx_resolved_case_space_status_created (space_id,status,created_at,id),
    ADD CONSTRAINT fk_resolved_case_space FOREIGN KEY (space_id) REFERENCES knowledge_space(space_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE security_event
    ADD COLUMN resource_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '可空安全事件目标资源类型' AFTER target_user_id,
    ADD COLUMN resource_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '可空目标资源公开标识；空间事件保存空间 UUID' AFTER resource_type,
    ADD KEY idx_security_event_resource_time (resource_type,resource_id,occurred_at,id);
