CREATE TABLE answer_feedback (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '反馈内部自增主键',
    run_id BINARY(16) NOT NULL COMMENT '被评价的成功 Agent 运行 UUID；每次运行最多一条当前反馈',
    user_id BINARY(16) NOT NULL COMMENT '提交反馈的用户 UUID，必须与运行发起人一致',
    rating VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '帮助程度：HELPFUL 或 NOT_HELPFUL；删除标记行为空',
    reason VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '无帮助原因：INCORRECT、UNSUPPORTED、INCOMPLETE、UNCLEAR、OTHER；有帮助或已删除时为空',
    note VARCHAR(1200) NULL COMMENT '可选说明，最多 300 个 Unicode 字符；禁止敏感信息',
    version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '反馈乐观锁版本，创建为 0，每次更正加 1',
    created_at DATETIME(6) NOT NULL COMMENT '首次创建 UTC 时间',
    updated_at DATETIME(6) NOT NULL COMMENT '最近更正 UTC 时间',
    deleted_at DATETIME(6) NULL COMMENT '用户删除 UTC 时间；删除时立即清空评分、原因和说明，仅保留无内容版本标记防并发 ABA',
    PRIMARY KEY (id),
    UNIQUE KEY uk_answer_feedback_run_id (run_id),
    KEY idx_answer_feedback_user_updated (user_id, updated_at, id),
    CONSTRAINT chk_answer_feedback_rating CHECK (rating IN ('HELPFUL', 'NOT_HELPFUL')),
    CONSTRAINT chk_answer_feedback_deleted CHECK (
        (deleted_at IS NULL AND rating IS NOT NULL) OR
        (deleted_at IS NOT NULL AND rating IS NULL AND reason IS NULL AND note IS NULL)
    ),
    CONSTRAINT chk_answer_feedback_reason CHECK (
        rating IS NULL OR
        (rating = 'HELPFUL' AND reason IS NULL) OR
        (rating = 'NOT_HELPFUL' AND reason IS NOT NULL
            AND reason IN ('INCORRECT', 'UNSUPPORTED', 'INCOMPLETE', 'UNCLEAR', 'OTHER'))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户对本人一次成功回答的当前主观反馈；不保存回答正文';
