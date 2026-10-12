CREATE TABLE human_evaluation_sample (
    sample_id BINARY(16) NOT NULL COMMENT '人工评测样本公开 UUID',
    source_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源类型 FIXED 或 FEEDBACK',
    source_ref VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定用例 ID 或成功运行 UUID',
    space_id BINARY(16) NOT NULL COMMENT '样本所属知识空间 UUID',
    question_text TEXT NOT NULL COMMENT '管理员确认脱敏后的用户问题副本',
    answer_text MEDIUMTEXT NOT NULL COMMENT '管理员确认脱敏后的模型回答副本',
    evidence_text MEDIUMTEXT NOT NULL COMMENT '管理员确认允许评审查看的证据副本',
    created_by BINARY(16) NOT NULL COMMENT '创建样本的管理员 UUID',
    review_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '评审状态 FIRST_PENDING SECOND_PENDING ARBITRATION_PENDING DECIDED',
    dataset_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '独立数据集层级 NONE DEVELOPMENT REGRESSION',
    dataset_version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '最近一次纳入或晋升的数据集全局版本，0 表示未纳入',
    created_at DATETIME(6) NOT NULL COMMENT '样本创建 UTC 时间',
    PRIMARY KEY (sample_id),
    UNIQUE KEY uk_human_evaluation_source (source_type, source_ref),
    KEY idx_human_evaluation_status_created (review_status, created_at, sample_id),
    CONSTRAINT chk_human_evaluation_source CHECK (source_type IN ('FIXED', 'FEEDBACK')),
    CONSTRAINT chk_human_evaluation_review_status CHECK (review_status IN ('FIRST_PENDING', 'SECOND_PENDING', 'ARBITRATION_PENDING', 'DECIDED')),
    CONSTRAINT chk_human_evaluation_dataset_state CHECK (dataset_state IN ('NONE', 'DEVELOPMENT', 'REGRESSION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='显式确认脱敏后的人工评测样本';

CREATE TABLE human_evaluation_review (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '评分内部主键',
    sample_id BINARY(16) NOT NULL COMMENT '评测样本 UUID',
    reviewer_id BINARY(16) NOT NULL COMMENT '独立评审管理员 UUID',
    review_role VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'FIRST SECOND 或 ARBITER',
    factual TINYINT UNSIGNED NOT NULL COMMENT '事实正确 0 到 2 分',
    citation TINYINT UNSIGNED NOT NULL COMMENT '引用支持 0 到 2 分',
    completeness TINYINT UNSIGNED NOT NULL COMMENT '完整性 0 到 2 分',
    actionability TINYINT UNSIGNED NOT NULL COMMENT '可操作性 0 到 2 分',
    clarity TINYINT UNSIGNED NOT NULL COMMENT '表达清晰度 0 到 2 分',
    reason VARCHAR(1000) NOT NULL COMMENT '人工评分理由，经过敏感信息校验',
    created_at DATETIME(6) NOT NULL COMMENT '评分提交 UTC 时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_human_review_reviewer (sample_id, reviewer_id),
    UNIQUE KEY uk_human_review_role (sample_id, review_role),
    CONSTRAINT chk_human_review_role CHECK (review_role IN ('FIRST', 'SECOND', 'ARBITER')),
    CONSTRAINT chk_human_review_factual CHECK (factual <= 2),
    CONSTRAINT chk_human_review_citation CHECK (citation <= 2),
    CONSTRAINT chk_human_review_completeness CHECK (completeness <= 2),
    CONSTRAINT chk_human_review_actionability CHECK (actionability <= 2),
    CONSTRAINT chk_human_review_clarity CHECK (clarity <= 2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每份样本最多三名独立管理员的不可变评分';

CREATE TABLE human_evaluation_dataset_change (
    version BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '人工评测数据集全局单调版本',
    sample_id BINARY(16) NOT NULL COMMENT '被纳入或晋升的样本 UUID',
    actor_id BINARY(16) NOT NULL COMMENT '执行纳入或审批的管理员 UUID',
    from_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '晋升前层级',
    to_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '晋升后层级',
    changed_at DATETIME(6) NOT NULL COMMENT '晋升 UTC 时间',
    PRIMARY KEY (version),
    UNIQUE KEY uk_human_dataset_sample_target (sample_id, to_state),
    KEY idx_human_dataset_changed_at (changed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='人工评测开发集与回归集显式晋升审计';
