CREATE TABLE ticket (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'MySQL 内部自增主键',
    ticket_no VARCHAR(13) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '对外工单编号，格式为 T 加 12 位数字',
    conversation_id BINARY(16) NULL COMMENT '创建工单的来源会话 UUID',
    source_turn_id BINARY(16) NULL COMMENT '触发工单建议的对话轮次 UUID',
    title VARCHAR(160) NOT NULL COMMENT '工单问题标题',
    problem_description TEXT NOT NULL COMMENT '问题现象、背景和错误信息',
    attempted_actions TEXT NULL COMMENT '用户已尝试的操作及结果',
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '工单状态：DRAFT、OPEN、RESOLVED 或 CLOSED',
    root_cause TEXT NULL COMMENT '人工确认的真实根因',
    solution MEDIUMTEXT NULL COMMENT '实际执行且有效的解决方案',
    close_reason VARCHAR(500) NULL COMMENT '未解决关闭工单的原因',
    version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by VARCHAR(100) NOT NULL COMMENT '创建操作者', created_at DATETIME(6) NOT NULL COMMENT '创建 UTC 时间',
    updated_by VARCHAR(100) NOT NULL COMMENT '最近修改操作者', updated_at DATETIME(6) NOT NULL COMMENT '最近修改 UTC 时间',
    resolved_by VARCHAR(100) NULL COMMENT '解决操作者', resolved_at DATETIME(6) NULL COMMENT '解决 UTC 时间',
    closed_by VARCHAR(100) NULL COMMENT '关闭操作者', closed_at DATETIME(6) NULL COMMENT '关闭 UTC 时间',
    PRIMARY KEY (id), UNIQUE KEY uk_ticket_ticket_no (ticket_no),
    KEY idx_ticket_status_created_at (status, created_at), KEY idx_ticket_conversation_id (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='技术支持工单';

CREATE TABLE managed_document (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '文档内部自增主键',
    title VARCHAR(160) NOT NULL COMMENT '知识文档标题',
    input_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '输入方式：MARKDOWN_FILE、TEXT_FILE 或 DIRECT_TEXT',
    original_file_name VARCHAR(255) NULL COMMENT '上传时原文件名', media_type VARCHAR(100) NULL COMMENT '服务端确认的媒体类型',
    raw_content LONGTEXT NOT NULL COMMENT 'UTF-8 解码后的完整原文',
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化原文 SHA-256',
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '文档状态：DRAFT、INDEXING、PUBLISHED、FAILED 或 ARCHIVED',
    version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁和知识版本',
    index_failure_reason VARCHAR(1000) NULL COMMENT '索引最终失败的脱敏摘要', archive_reason VARCHAR(500) NULL COMMENT '文档退出检索的人工原因',
    deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '草稿软删除标识：FALSE 否、TRUE 是', deleted_by VARCHAR(100) NULL COMMENT '删除操作者', deleted_at DATETIME(6) NULL COMMENT '删除 UTC 时间',
    created_by VARCHAR(100) NOT NULL COMMENT '创建操作者', created_at DATETIME(6) NOT NULL COMMENT '创建 UTC 时间',
    updated_by VARCHAR(100) NOT NULL COMMENT '最近修改操作者', updated_at DATETIME(6) NOT NULL COMMENT '最近修改 UTC 时间',
    published_by VARCHAR(100) NULL COMMENT '发布操作者', published_at DATETIME(6) NULL COMMENT '发布 UTC 时间',
    archived_by VARCHAR(100) NULL COMMENT '归档操作者', archived_at DATETIME(6) NULL COMMENT '归档 UTC 时间',
    PRIMARY KEY (id), KEY idx_managed_document_status_created_at (status, created_at),
    KEY idx_managed_document_content_hash (content_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='托管知识文档';

CREATE TABLE resolved_case (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '案例内部自增主键', source_ticket_id BIGINT UNSIGNED NOT NULL COMMENT '来源已解决工单内部 ID',
    title VARCHAR(160) NOT NULL COMMENT '案例标题', problem TEXT NOT NULL COMMENT '问题现象和背景', cause TEXT NOT NULL COMMENT '人工确认根因', solution MEDIUMTEXT NOT NULL COMMENT '已验证解决步骤',
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '案例状态', content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化案例内容 SHA-256', version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁和知识版本',
    publish_failure_reason VARCHAR(1000) NULL COMMENT '发布最终失败脱敏摘要', rejection_reason VARCHAR(500) NULL COMMENT '人工拒绝原因', archive_reason VARCHAR(500) NULL COMMENT '案例归档原因',
    deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '草稿软删除标识：FALSE 否、TRUE 是', deleted_by VARCHAR(100) NULL COMMENT '删除操作者', deleted_at DATETIME(6) NULL COMMENT '删除 UTC 时间',
    created_by VARCHAR(100) NOT NULL COMMENT '创建操作者或系统身份', created_at DATETIME(6) NOT NULL COMMENT '创建 UTC 时间', updated_by VARCHAR(100) NOT NULL COMMENT '最近修改操作者', updated_at DATETIME(6) NOT NULL COMMENT '最近修改 UTC 时间',
    published_by VARCHAR(100) NULL COMMENT '发布操作者', published_at DATETIME(6) NULL COMMENT '发布 UTC 时间', archived_by VARCHAR(100) NULL COMMENT '归档操作者', archived_at DATETIME(6) NULL COMMENT '归档 UTC 时间',
    PRIMARY KEY (id), KEY idx_resolved_case_source_ticket_id (source_ticket_id), KEY idx_resolved_case_status_created_at (status, created_at),
    CONSTRAINT fk_resolved_case_ticket FOREIGN KEY (source_ticket_id) REFERENCES ticket (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='已解决工单沉淀的审核案例';

CREATE TABLE async_task (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '异步任务内部自增主键', task_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '任务类型', aggregate_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联聚合类型', aggregate_id BIGINT UNSIGNED NOT NULL COMMENT '关联业务对象内部 ID', aggregate_version BIGINT UNSIGNED NOT NULL COMMENT '任务创建时聚合版本',
    idempotency_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '内部任务创建幂等键', status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '任务执行状态', attempt_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已开始执行次数', max_attempts INT UNSIGNED NOT NULL DEFAULT 3 COMMENT '最大执行次数', next_run_at DATETIME(6) NOT NULL COMMENT '下一次允许调度 UTC 时间',
    locked_by VARCHAR(160) NULL COMMENT '持有执行锁的实例标识', locked_until DATETIME(6) NULL COMMENT '任务锁租约截止时间', last_error_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '最后失败稳定错误码', last_error_message VARCHAR(1000) NULL COMMENT '最后失败脱敏摘要', retry_of_task_id BIGINT UNSIGNED NULL COMMENT '人工重试依据的原任务 ID', manual_retry_reason VARCHAR(500) NULL COMMENT '人工重试原因',
    created_by VARCHAR(100) NOT NULL COMMENT '创建操作者或系统身份', created_at DATETIME(6) NOT NULL COMMENT '创建 UTC 时间', started_at DATETIME(6) NULL COMMENT '最近一次开始 UTC 时间', finished_at DATETIME(6) NULL COMMENT '最终结束 UTC 时间', updated_at DATETIME(6) NOT NULL COMMENT '最近状态更新 UTC 时间',
    PRIMARY KEY (id), UNIQUE KEY uk_async_task_idempotency_key (idempotency_key), KEY idx_async_task_schedule (status, next_run_at), KEY idx_async_task_aggregate (aggregate_type, aggregate_id),
    CONSTRAINT fk_async_task_retry_of FOREIGN KEY (retry_of_task_id) REFERENCES async_task (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='MySQL 持久化异步工作队列';

CREATE TABLE idempotency_record (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '幂等记录内部自增主键', operator_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '操作人标识', operation_type VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '业务操作类型', idempotency_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '客户端幂等标识', request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化请求 SHA-256', status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '幂等执行状态',
    resource_type VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '成功结果资源类型', resource_id BIGINT UNSIGNED NULL COMMENT '成功结果资源内部 ID', response_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '稳定业务结果码', failure_message VARCHAR(1000) NULL COMMENT '脱敏失败摘要', locked_until DATETIME(6) NULL COMMENT '处理中租约截止时间', created_at DATETIME(6) NOT NULL COMMENT '首次接收 UTC 时间', updated_at DATETIME(6) NOT NULL COMMENT '最近更新 UTC 时间', expires_at DATETIME(6) NOT NULL COMMENT '幂等保证截止 UTC 时间',
    PRIMARY KEY (id), UNIQUE KEY uk_idempotency_record_operator_operation_key (operator_id, operation_type, idempotency_key), KEY idx_idempotency_record_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='外部写请求幂等记录';

CREATE TABLE agent_run (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '运行记录内部自增主键', run_id BINARY(16) NOT NULL COMMENT 'Agent 执行公开 UUID', conversation_id BINARY(16) NOT NULL COMMENT '所属会话 UUID', client_message_id BINARY(16) NOT NULL COMMENT '用户消息幂等 UUID', intent VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '最终路由意图', intent_confidence DECIMAL(5,4) NULL COMMENT '意图置信度 0 到 1', intent_reason_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '意图识别稳定原因码', retrieval_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '检索三态结果', prompt_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Prompt 短 SHA-256 版本', schema_version VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '结构化输出 Schema 版本', chat_model VARCHAR(160) NULL COMMENT '实际 Chat 模型名', embedding_model VARCHAR(160) NULL COMMENT '实际 Embedding 模型名', rerank_model VARCHAR(160) NULL COMMENT '实际 Rerank 模型名', status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '运行结果状态', error_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '失败稳定错误码', duration_ms BIGINT UNSIGNED NULL COMMENT '总耗时毫秒', created_at DATETIME(6) NOT NULL COMMENT '创建 UTC 时间', finished_at DATETIME(6) NULL COMMENT '结束 UTC 时间',
    PRIMARY KEY (id), UNIQUE KEY uk_agent_run_run_id (run_id), KEY idx_agent_run_conversation_created (conversation_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Agent 运行安全摘要';

CREATE TABLE retrieval_trace (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '检索轨迹内部自增主键', run_id BINARY(16) NOT NULL COMMENT '关联 Agent 运行 UUID，不建外键', standalone_query_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '独立检索问题 SHA-256', bm25_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'BM25 分支状态', vector_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '向量分支状态', rerank_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '重排分支状态', rerank_degraded BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否使用 RRF 保守门槛', grounded_threshold DECIMAL(8,6) NULL COMMENT '本次可靠知识阈值', candidate_scores_json JSON NULL COMMENT '最多 30 个候选的安全分数摘要', selected_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '最终证据分块数量', retrieval_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '最终检索三态结果', duration_ms BIGINT UNSIGNED NULL COMMENT '检索耗时毫秒', created_at DATETIME(6) NOT NULL COMMENT '创建 UTC 时间',
    PRIMARY KEY (id), KEY idx_retrieval_trace_run_id (run_id), KEY idx_retrieval_trace_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='受控检索链路摘要';
