# 领域模型与字段字典

本文第 1～12 节保留一期逻辑数据模型。三期新增的用户、会话摘要、长期记忆与安全审计字段以本文第 13 节和 [当前系统基线](10-current-system-baseline.md) 为当前语义依据。

## 1. 通用数据约定

- MySQL 使用 `InnoDB`、`utf8mb4` 和 `utf8mb4_0900_ai_ci`。
- 编号、哈希、幂等键等精确标识字段使用区分大小写的排序规则。
- 内部主键统一为 `BIGINT UNSIGNED AUTO_INCREMENT`，字段名为 `id`。
- 内部主键经 API 返回时序列化为字符串，避免 JavaScript 大整数精度丢失。
- UUID 在 Java 中使用 `UUID`，API 和 Redis 使用标准 UUID 字符串，MySQL 使用 `BINARY(16)`。
- 时间统一按 UTC 存储为 `DATETIME(6)`；Java 使用 `Instant`；API 使用 ISO 8601。
- 状态和类型枚举保存稳定英文字符串，不保存枚举序号。
- `version` 初始值为 0，每次成功业务修改加 1。
- 空字符串不能代替 `NULL`；只有“尚未发生”或“不适用”的字段才能为空。
- 业务表不使用 `ON DELETE CASCADE`。
- 表和字段使用 `snake_case`，Java 属性使用 `camelCase`。
- 索引命名为 `idx_{table}_{columns}`，唯一索引为 `uk_{table}_{columns}`，外键为 `fk_{table}_{target}`。

## 2. 标识符字典

| 标识符 | 含义 |
|---|---|
| `ticketNo` | 工单对外编号，格式 `T` + 12 位数字，例如 `T000000000001` |
| `conversationId` | 服务端生成的会话 UUID，用于串联多轮对话 |
| `clientMessageId` | 客户端为一条用户消息生成的 UUID，用于聊天幂等 |
| `runId` | 一次 Agent 执行的 UUID，用于关联日志、SSE、检索轨迹和模型调用 |
| `eventId` | 单个 SSE 事件的 UUID，一期只用于排障，不支持断线续传 |
| `suggestionId` | 一次创建工单建议的 UUID，绑定特定会话轮次 |
| `evaluationRunId` | 一次开发环境检索评估运行的 UUID |
| `chunkId` | 稳定知识分块标识，按来源、版本和序号确定性生成 |

## 3. 工单 `ticket`

| 字段 | 可空 | 含义与约束 |
|---|---:|---|
| `id` | 否 | MySQL 内部自增主键，只用于表间关联 |
| `ticket_no` | 否 | 对外工单编号；先取得内部 ID，再生成 `T` + 12 位数字 |
| `conversation_id` | 是 | 创建此工单的来源会话；手工创建时为空，Redis 过期后仍保留 |
| `source_turn_id` | 是 | 触发工单建议的具体对话轮次；手工创建时为空 |
| `title` | 否 | 简短问题标题，AI 可生成，草稿阶段可人工编辑 |
| `problem_description` | 否 | 问题现象、背景和错误信息，不包含虚构事实 |
| `attempted_actions` | 是 | 用户已经尝试的操作及结果；不是最终解决方案 |
| `status` | 否 | `DRAFT`、`OPEN`、`RESOLVED` 或 `CLOSED` |
| `root_cause` | 是 | 人工确认的真实根因；解决工单时必填，其他状态为空 |
| `solution` | 是 | 实际执行且有效的解决方案；解决工单时必填 |
| `close_reason` | 是 | 未解决而关闭工单的原因；关闭时必填 |
| `version` | 否 | 乐观锁版本，防止并发覆盖 |
| `created_by` | 否 | 创建人，一期固定 `dev-operator` |
| `created_at` | 否 | 工单创建 UTC 时间 |
| `updated_by` | 否 | 最近修改人 |
| `updated_at` | 否 | 最近修改 UTC 时间 |
| `resolved_by` | 是 | 执行解决操作的人，仅 `RESOLVED` 有值 |
| `resolved_at` | 是 | 工单被解决的 UTC 时间 |
| `closed_by` | 是 | 执行关闭操作的人，仅 `CLOSED` 有值 |
| `closed_at` | 是 | 工单被关闭的 UTC 时间 |

工单不提供物理删除。`RESOLVED` 和 `CLOSED` 为一期终态。

## 4. 托管文档 `managed_document`

| 字段 | 可空 | 含义与约束 |
|---|---:|---|
| `id` | 否 | 文档内部自增主键 |
| `title` | 否 | 知识文档标题，1～160 字符 |
| `input_type` | 否 | 输入方式：`MARKDOWN_FILE`、`TEXT_FILE`、`DIRECT_TEXT` |
| `original_file_name` | 是 | 上传时的原文件名；直接文本输入时为空 |
| `media_type` | 是 | 服务端检测和确认的媒体类型，不信任客户端声明 |
| `raw_content` | 否 | UTF-8 解码后的完整原文，使用 `LONGTEXT`，最大输入 1 MiB |
| `content_hash` | 否 | 规范化原文的 SHA-256，用于重复内容检测和发布校验 |
| `status` | 否 | `DRAFT`、`INDEXING`、`PUBLISHED`、`FAILED` 或 `ARCHIVED` |
| `version` | 否 | 文档乐观锁和知识版本 |
| `index_failure_reason` | 是 | 索引最终失败的脱敏摘要；成功或未发布时为空 |
| `archive_reason` | 是 | 文档退出检索的人工原因；归档时必填 |
| `deleted` | 否 | 从未发布的草稿是否已软删除，0 否、1 是 |
| `deleted_by` | 是 | 删除草稿的操作人 |
| `deleted_at` | 是 | 删除草稿的 UTC 时间 |
| `created_by` | 否 | 创建文档的操作人 |
| `created_at` | 否 | 文档创建 UTC 时间 |
| `updated_by` | 否 | 最近修改人 |
| `updated_at` | 否 | 最近修改 UTC 时间 |
| `published_by` | 是 | Elasticsearch 完整发布成功后记入的发布人 |
| `published_at` | 是 | Elasticsearch 完整发布成功的 UTC 时间 |
| `archived_by` | 是 | 执行归档的人 |
| `archived_at` | 是 | 文档进入 `ARCHIVED` 的 UTC 时间 |

从未发布的 `DRAFT` 可软删除；发布过的内容只能归档。

## 5. 已解决案例 `resolved_case`

| 字段 | 可空 | 含义与约束 |
|---|---:|---|
| `id` | 否 | 案例内部自增主键 |
| `source_ticket_id` | 否 | 生成案例所依据的已解决工单内部 ID |
| `title` | 否 | 案例标题，1～160 字符 |
| `problem` | 否 | 问题现象和适用背景，1～4000 字符 |
| `cause` | 否 | 来源工单中已由人工确认的根因，1～4000 字符 |
| `solution` | 否 | 来源工单中已验证的解决步骤，1～8000 字符 |
| `status` | 否 | `DRAFT`、`PUBLISHING`、`PUBLISHED`、`PUBLISH_FAILED`、`REJECTED` 或 `ARCHIVED` |
| `content_hash` | 否 | 案例规范化内容的 SHA-256，用于版本和发布校验 |
| `version` | 否 | 案例乐观锁和知识版本 |
| `publish_failure_reason` | 是 | 发布任务最终失败的脱敏摘要 |
| `rejection_reason` | 是 | 人工拒绝案例进入知识库的原因 |
| `archive_reason` | 是 | 已发布案例退出检索的原因 |
| `deleted` | 否 | 从未发布草稿是否软删除；一期主要通过拒绝表达审核结论 |
| `deleted_by` | 是 | 删除草稿的操作人 |
| `deleted_at` | 是 | 删除草稿的 UTC 时间 |
| `created_by` | 否 | 案例草稿创建者；异步生成时为系统身份 |
| `created_at` | 否 | 案例草稿创建 UTC 时间 |
| `updated_by` | 否 | 最近修改人 |
| `updated_at` | 否 | 最近修改 UTC 时间 |
| `published_by` | 是 | 人工发起且索引成功后的发布人 |
| `published_at` | 是 | Elasticsearch 完整发布成功时间 |
| `archived_by` | 是 | 执行归档的人 |
| `archived_at` | 是 | 案例归档 UTC 时间 |

同一工单同时最多存在一个非归档案例。AI 只能整理来源工单中已有事实，不得新增产品、版本、命令、原因或处理结果。

## 6. 异步任务 `async_task`

| 字段 | 可空 | 含义与约束 |
|---|---:|---|
| `id` | 否 | 异步任务内部自增主键；API 按字符串返回 |
| `task_type` | 否 | `CASE_GENERATION`、`KNOWLEDGE_INDEX` 或 `KNOWLEDGE_DELETE` |
| `aggregate_type` | 否 | 关联对象类型：`TICKET`、`MANAGED_DOCUMENT` 或 `RESOLVED_CASE` |
| `aggregate_id` | 否 | 关联业务对象内部 ID |
| `aggregate_version` | 否 | 创建任务时对象版本，阻止旧任务覆盖新内容 |
| `idempotency_key` | 否 | 内部任务创建幂等键，防止同一业务动作重复投递 |
| `status` | 否 | `PENDING`、`RUNNING`、`RETRY_WAIT`、`SUCCEEDED`、`DEAD` 或 `CANCELLED` |
| `attempt_count` | 否 | 已开始执行的次数 |
| `max_attempts` | 否 | 最大执行次数，一期默认为 3 |
| `next_run_at` | 否 | 下一次允许调度的 UTC 时间 |
| `locked_by` | 是 | 当前持有执行锁的应用实例标识，仅 Worker 内部使用 |
| `locked_until` | 是 | 任务执行锁自动失效时间 |
| `last_error_code` | 是 | 最后一次失败的稳定机器错误码 |
| `last_error_message` | 是 | 最后一次失败的脱敏摘要 |
| `retry_of_task_id` | 是 | 人工重试所依据的原 `DEAD` 任务；首次任务为空 |
| `manual_retry_reason` | 是 | 人工发起重试的原因；自动任务为空 |
| `created_by` | 否 | 创建任务的操作人或固定系统身份 |
| `created_at` | 否 | 任务创建 UTC 时间 |
| `started_at` | 是 | 最近一次开始执行的 UTC 时间 |
| `finished_at` | 是 | 最终成功、死亡或取消的 UTC 时间 |
| `updated_at` | 否 | 最近状态更新时间 |

调度参数：每 2 秒轮询，单批 10 条，并发数 2，锁租约 5 分钟；最多 3 次，退避 30 秒、2 分钟、10 分钟。多个实例使用 `FOR UPDATE SKIP LOCKED` 抢占任务。

## 7. 外部请求幂等 `idempotency_record`

| 字段 | 可空 | 含义与约束 |
|---|---:|---|
| `id` | 否 | 幂等记录内部自增主键 |
| `operator_id` | 否 | 发起操作的操作者，一期固定 `dev-operator` |
| `operation_type` | 否 | 业务操作类型，例如创建草稿、解决工单、发布文档 |
| `idempotency_key` | 否 | 客户端幂等标识；同一操作重试必须保持不变 |
| `request_hash` | 否 | 规范化请求参数的 SHA-256，阻止同一个 Key 携带不同参数 |
| `status` | 否 | `PROCESSING`、`SUCCEEDED`、`FAILED_RETRYABLE` 或 `FAILED_FINAL` |
| `resource_type` | 是 | 成功操作所对应的资源类型 |
| `resource_id` | 是 | 成功结果对应的资源 ID，用于重查首次结果 |
| `response_code` | 是 | 首次执行产生的稳定业务结果码 |
| `failure_message` | 是 | 脱敏失败摘要，不保存堆栈和模型原文 |
| `locked_until` | 是 | 处理中记录的租约截止时间，防止实例宕机后永久占用 |
| `created_at` | 否 | 首次收到请求的 UTC 时间 |
| `updated_at` | 否 | 最近状态更新时间 |
| `expires_at` | 否 | 幂等保证截止时间，一期默认 7 天 |

唯一约束为 `(operator_id, operation_type, idempotency_key)`。

## 8. Agent 运行 `agent_run`

该表保存摘要和关联信息，不保存完整 Prompt、完整回答、知识正文、工具完整数据或隐藏推理。

| 字段 | 可空 | 含义 |
|---|---:|---|
| `id` | 否 | 内部自增主键 |
| `run_id` | 否 | 一次 Agent 执行的公开 UUID |
| `conversation_id` | 否 | 本次运行所属会话 |
| `client_message_id` | 否 | 触发运行的用户消息幂等 ID |
| `intent` | 是 | 最终路由意图 |
| `intent_confidence` | 是 | 意图置信度，范围 0～1 |
| `intent_reason_code` | 是 | 规则命中、模型识别或降级的原因码 |
| `retrieval_status` | 是 | `GROUNDED`、`NO_RELIABLE_KNOWLEDGE` 或 `RETRIEVAL_FAILED` |
| `prompt_version` | 是 | 本次使用 Prompt 内容的短 SHA-256 版本 |
| `schema_version` | 是 | 本次结构化输出 Schema 的版本 |
| `chat_model` | 是 | 实际使用的 Chat 模型名 |
| `embedding_model` | 是 | 实际使用的 Embedding 模型名 |
| `rerank_model` | 是 | 实际使用的 Rerank 模型名 |
| `status` | 否 | 本次运行成功、失败或被中断的结果状态 |
| `error_code` | 是 | 运行失败的稳定错误码 |
| `duration_ms` | 是 | 总耗时，单位毫秒 |
| `created_at` | 否 | 运行记录创建 UTC 时间 |
| `finished_at` | 是 | 运行结束 UTC 时间 |

## 9. 检索轨迹 `retrieval_trace`

| 字段 | 可空 | 含义 |
|---|---:|---|
| `id` | 否 | 内部自增主键 |
| `run_id` | 否 | 关联 `agent_run.run_id`，日志表不建数据库外键 |
| `standalone_query_hash` | 否 | 独立检索问题的哈希，不保存完整敏感问题文本 |
| `bm25_status` | 否 | BM25 分支成功、失败或跳过状态 |
| `vector_status` | 否 | 向量分支成功、失败或跳过状态 |
| `rerank_status` | 否 | Rerank 成功、降级、失败或跳过状态 |
| `rerank_degraded` | 否 | 是否使用 RRF 保守门槛替代 Rerank，0 否、1 是 |
| `grounded_threshold` | 是 | 本次采用的可靠知识阈值 |
| `candidate_scores_json` | 是 | 最多 30 个候选的 ID、来源、各阶段排名、分数和是否入选 |
| `selected_count` | 否 | 最终作为模型证据的分块数量 |
| `retrieval_status` | 否 | 最终三态检索结果 |
| `duration_ms` | 是 | 检索链路耗时，单位毫秒 |
| `created_at` | 否 | 轨迹创建 UTC 时间 |

## 10. Redis 会话模型

主 Key：`support-agent:conversation:{userId}:{conversationId}`，空闲 TTL 为 7 天。

| 字段 | 含义 |
|---|---|
| `conversationId` | 会话公开 UUID |
| `userId` | 会话所属用户；一期固定为 `dev-operator`，阶段 11 后为已认证用户 UUID |
| `version` | 成功完成一轮后递增的会话版本 |
| `status` | `IDLE` 或 `RUNNING` |
| `activeRunId` | 当前运行的 Agent 执行 ID，空闲时为空 |
| `runLeaseUntil` | 运行锁自动失效时间，一期为 3 分钟 |
| `lastSequence` | 最近 SSE 事件序号 |
| `lastAccessAt` | 最近访问时间，用于续期 TTL |
| `turns` | 最近最多 20 个完整轮次 |
| `agentState` | AgentScope 状态存储所需序列化内容 |
| `lastSuggestedTicketContext` | 最近工单建议冻结的受控上下文，最多 8000 字符 |

每个 `turn`：

| 字段 | 含义 |
|---|---|
| `turnId` | 本轮 UUID |
| `clientMessageId` | 用户消息幂等 ID |
| `userMessage` | 用户原始输入，最大 4000 字符 |
| `standaloneQuery` | 意图识别生成、仅用于检索的独立问题 |
| `intent` | 本轮最终意图 |
| `assistantAnswer` | 通过最终校验的完整回答 |
| `retrievalStatus` | 本轮三态检索结果 |
| `citationRefs` | 最终有效引用的轻量元数据 |
| `ticketSuggested` | 本轮是否产生工单建议 |
| `completedAt` | 本轮成功完成时间 |

失败时不写入半轮对话、不递增版本。工具调用和工具结果成对保留或成对淘汰，Redis 不保存隐藏推理。

## 11. Elasticsearch 分块文档

物理索引初始为 `support_knowledge_v1`，业务别名为 `support_knowledge_current`。

| 字段 | 含义 |
|---|---|
| `chunkId` | 分块稳定标识，也是调用端关联候选的依据 |
| `sourceType` | `MANAGED_DOCUMENT` 或 `RESOLVED_CASE` |
| `sourceId` | MySQL 来源记录 ID |
| `sourceVersion` | 发布时来源版本 |
| `chunkIndex` | 同一来源版本内从 0 开始的分块序号 |
| `title` | 来源标题，BM25 权重 3 |
| `headingPath` | Markdown 标题层级路径或文本上下文路径，权重 2 |
| `content` | 分块正文，BM25 权重 1 |
| `exactTerms` | 确定性提取的精确技术词，权重 5 |
| `contentHash` | 分块内容哈希，用于完整性校验 |
| `embedding` | `text-embedding-v4` 生成的 1024 维向量 |
| `publishedAt` | 来源正式发布 UTC 时间 |
| `indexedAt` | 此分块写入 Elasticsearch 的 UTC 时间 |

Elasticsearch `_id` 为 `{sourceType}:{sourceId}:{sourceVersion}:{chunkIndex}`。索引只保存已发布内容，不再冗余业务状态字段。

## 12. 数据保留

- 工单、托管文档、案例和审计字段一期不自动清理。
- `agent_run`、`retrieval_trace` 默认保留 90 天。
- 成功异步任务保留 30 天；失败和死信任务保留 90 天。
- 外部幂等记录保留 7 天。
- Redis 会话连续 7 天未访问后自动过期。
- 清理按批次执行，不使用数据库级联删除。

## 13. 三期新增数据模型摘要

### 13.1 本地用户 `app_user`

| 字段 | 可空 | 含义与约束 |
|---|---:|---|
| `id` | 否 | MySQL 内部自增主键，不通过 API 暴露 |
| `user_id` | 否 | 用户公开 UUID，创建后不可变 |
| `username` | 否 | 规范化为小写的唯一登录名，3～64 字符 |
| `display_name` | 否 | 用户展示名称，最长 100 字符，不参与登录 |
| `password_hash` | 否 | BCrypt 密码哈希，禁止通过接口或日志暴露 |
| `role` | 否 | 两级角色：`USER` 或 `ADMIN` |
| `status` | 否 | 账号状态：`ACTIVE` 或 `DISABLED` |
| `version` | 否 | 账号乐观锁版本，初始为 0 |
| `password_changed_at` | 否 | 最近一次设置密码的 UTC 时间 |
| `must_change_password` | 否 | 是否必须先修改管理员设置的一次性密码 |
| `locked_until` | 是 | 临时锁定截止 UTC 时间；空值表示未锁定 |
| `created_by` | 否 | 创建者公开用户 UUID 或 `SYSTEM_BOOTSTRAP` |
| `created_at` | 否 | 创建 UTC 时间 |
| `updated_by` | 否 | 最近修改者公开用户 UUID、认证系统身份或 `SYSTEM_BOOTSTRAP` |
| `updated_at` | 否 | 最近修改 UTC 时间 |

### 13.2 长期记忆开关 `user_memory_settings`

| 字段 | 可空 | 含义与约束 |
|---|---:|---|
| `id` | 否 | MySQL 内部自增主键，不通过 API 暴露 |
| `user_id` | 否 | 设置所属用户的公开 UUID，每个用户最多一行 |
| `enabled` | 否 | 是否允许生成和注入长期记忆，默认 `false` |
| `version` | 否 | 设置修改使用的乐观锁版本 |
| `created_at` | 否 | 设置首次创建 UTC 时间 |
| `updated_at` | 否 | 设置最近修改 UTC 时间 |

### 13.3 用户长期记忆 `user_memory`

| 字段 | 可空 | 含义与约束 |
|---|---:|---|
| `id` | 否 | MySQL 内部自增主键，不通过 API 暴露 |
| `memory_id` | 否 | 对外使用的长期记忆 UUID |
| `user_id` | 否 | 记忆所有者公开 UUID |
| `memory_type` | 否 | `PREFERENCE`、`CONSTRAINT` 或 `ENVIRONMENT` |
| `content` | 否 | 用户可见正文，最多 500 个 Unicode 字符 |
| `content_hash` | 否 | 规范化正文 SHA-256，用于同用户同类型去重 |
| `status` | 否 | `PROPOSED`、`ACTIVE` 或 `REVOKED` |
| `pinned` | 否 | 用户是否显式固定并提高注入优先级，默认 `false` |
| `source_conversation_id` | 否 | 产生候选的公开会话 UUID |
| `source_turn_id` | 否 | 产生候选的客户端消息 UUID |
| `expires_at` | 是 | 可选 UTC 失效时间；到期后不再注入上下文 |
| `version` | 否 | 确认、更正和撤销使用的乐观锁版本 |
| `created_by` | 否 | 候选创建主体；模型候选固定为 `MODEL_CANDIDATE` |
| `created_at` | 否 | 候选创建 UTC 时间，也是 30 天候选清理依据 |
| `confirmed_by` | 是 | 确认候选的公开用户 UUID |
| `confirmed_at` | 是 | 候选确认 UTC 时间 |
| `updated_by` | 否 | 最近修改主体 |
| `updated_at` | 否 | 最近修改 UTC 时间 |
| `revoked_by` | 是 | 撤销记忆的公开用户 UUID |
| `revoked_at` | 是 | 记忆撤销 UTC 时间 |

### 13.4 安全事件 `security_event`

| 字段 | 可空 | 含义与约束 |
|---|---:|---|
| `id` | 否 | 安全事件内部自增主键 |
| `event_type` | 否 | 登录、密码、角色、状态、解锁或 Token 撤销的稳定事件类型 |
| `target_user_id` | 是 | 目标用户公开 UUID；未知账号登录失败时为空 |
| `actor_id` | 否 | 操作者公开 UUID、`ANONYMOUS` 或稳定系统身份 |
| `result` | 否 | `SUCCEEDED` 或 `DENIED` |
| `reason` | 否 | 不含正文的低基数原因分类 |
| `source_hash` | 是 | 客户端来源 SHA-256；不保存原始地址 |
| `occurred_at` | 否 | 事件发生 UTC 时间 |

### 13.5 既有表归属增量

| 字段 | 可空 | 含义与约束 |
|---|---:|---|
| `ticket.owner_user_id` | 是 | 工单所有者公开 UUID；空值表示阶段 11 前历史工单，只允许管理员访问 |
| `agent_run.user_id` | 是 | 发起本次 Agent 运行的用户 UUID；空值表示历史运行，只用于安全关联 |

Redis 会话当前包含 `ownerUserId`、`generation`、结构化滚动摘要、最近完整轮次和用户有序索引。会话空闲 TTL 仍为 7 天；模型上下文保留最近 6 个完整轮次并受 Token 预算控制，不再简单依赖“最多 20 轮后直接丢弃”的一期策略。

Redis 认证只保存 Token SHA-256 和用户 Token ZSET 索引。Token 固定有效期为 2 小时，每用户最多 5 个有效 Token；登录失败键只保存用户名或客户端来源的不可逆哈希、失败次数和锁定截止时间。

`security_event` 不保存用户名、密码、原始 Token、Token 哈希或请求正文。`user_memory` 候选必须由用户确认后才能注入；每用户最多保留 100 条未永久删除的记忆。
