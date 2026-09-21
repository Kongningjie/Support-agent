# API、统一响应与 SSE 契约

> 第 1～10 节保留一期 API 基线；阶段 11～14 新增接口以第 11 节和 [当前系统基线](10-current-system-baseline.md) 为准。除登录、健康检查和开发文档外，当前业务接口均要求 Bearer Token。

## 1. 通用规则

- REST 基础路径为 `/api/v1`。
- 普通 JSON API 使用 `ApiResult<T>`；SSE 使用独立事件协议，不套 `ApiResult`。
- HTTP 状态必须保留协议语义，禁止所有结果都返回 200。
- 分页信息放在 `PageResult<T>` 中，不增加 `ApiResult` 顶层字段。
- API DTO 不复用领域对象、MyBatis DO 或 Elasticsearch Document。
- 所有 OpenAPI 字段必须注明中文含义、是否可空、长度限制和示例。
- 当前操作者来自已认证 `AuthenticatedUser`，不读取客户端伪造的身份头；系统异步任务使用稳定系统身份。

## 2. `ApiResult<T>`

| 字段 | 含义 |
|---|---|
| `code` | 稳定、机器可读的业务结果码；成功固定为 `SUCCESS` |
| `message` | 面向调用方的简体中文说明，不包含堆栈、密钥或内部地址 |
| `data` | 成功数据或允许公开的错误详情；无数据时为空 |
| `traceId` | 当前 HTTP 请求追踪标识，用于关联服务端日志 |
| `timestamp` | 服务端生成响应的 UTC 时间，ISO 8601 格式 |

`PageResult<T>` 字段：

| 字段 | 含义 |
|---|---|
| `items` | 当前页数据列表 |
| `page` | 当前页码，从 1 开始 |
| `size` | 每页数量，默认 20，最大 100 |
| `totalElements` | 满足条件的数据总数 |
| `totalPages` | 按当前 `size` 计算的总页数 |

分页默认按 `createdAt` 倒序，同一时间下以 `id` 倒序保证稳定。不允许客户端传任意数据库字段作为排序字段。

## 3. 聊天 SSE

### 3.1 请求

`POST /api/v1/chat/stream`，请求头 `Accept: text/event-stream`。

| 字段 | 首次会话 | 后续会话 | 含义 |
|---|---:|---:|---|
| `conversationId` | 可空 | 必填 | 服务端会话 UUID；首次为空时由服务端创建 |
| `clientMessageId` | 必填 | 必填 | 本条用户消息的 UUID 幂等标识 |
| `message` | 必填 | 必填 | 用户消息，去除首尾空格后 1～4000 字符 |
| `expectedConversationVersion` | 不适用 | 必填 | 客户端认为的当前会话版本，用于并发控制 |

### 3.2 通用事件字段

| 字段 | 含义 |
|---|---|
| `eventId` | 单个事件 UUID |
| `eventType` | 稳定业务事件类型 |
| `runId` | 本次 Agent 执行 UUID |
| `conversationId` | 所属会话 UUID |
| `sequence` | 本次会话内单调递增的事件序号 |
| `timestamp` | 事件产生的 UTC 时间 |
| `data` | 事件类型对应的数据对象 |

事件顺序按实际分支从以下集合产生：

- `conversation.started`：会话已经创建或本轮已经取得执行权。
- `retrieval.started`：开始执行受控知识检索。
- `retrieval.completed`：检索完成，包含三态结果和安全摘要。
- `answer.started`：准备输出已缓冲和校验的答案。
- `answer.delta`：安全文本片段，不是 AgentScope 原始 Token 事件。
- `citation`：一条结构化引用元数据。
- `ticket.suggested`：无可靠知识时产生的工单建议和 `suggestionId`。
- `answer.completed`：完整最终答案、有效引用、会话新版本和结果状态。
- `error`：稳定错误码、可公开消息和是否建议重试。

一期不支持 `Last-Event-ID` 和断线重放。客户端断开后尽力中断 Agent；服务端不保存半轮对话。

### 3.3 安全缓冲

模型输出不会逐 Token 原样转发。服务端先按句子或安全片段缓冲，执行敏感信息、引用和精确词校验后再发送。完整回答最大 8000 字符。已经发送客户端过慢时终止该次流，不能无限缓存。

## 4. 工单 API

| 方法与路径 | 用途 | 核心请求字段 |
|---|---|---|
| `POST /tickets/drafts/from-conversation` | 消费工单建议并生成草稿 | `conversationId`：来源会话；`suggestionId`：绑定轮次的建议；`idempotencyKey`：操作幂等键 |
| `POST /tickets/drafts` | 手工创建草稿 | `title`、`problemDescription`、可空 `attemptedActions`、`idempotencyKey` |
| `GET /tickets/{ticketNo}` | 按对外编号查询详情 | `ticketNo`：`T` + 12 位数字 |
| `PUT /tickets/{ticketNo}/draft` | 修改草稿 | `title`、`problemDescription`、可空 `attemptedActions`、`version` |
| `POST /tickets/{ticketNo}/submit` | `DRAFT -> OPEN` | `version`、`idempotencyKey` |
| `POST /tickets/{ticketNo}/resolve` | `OPEN -> RESOLVED` | `rootCause`、`solution`、`version`、`idempotencyKey` |
| `POST /tickets/{ticketNo}/close` | `DRAFT/OPEN -> CLOSED` | `closeReason`、`version`、`idempotencyKey` |
| `GET /tickets` | 分页查询 | 可空 `status`、可空 `keyword`、`page`、`size` |

解决工单时，在同一事务创建 `CASE_GENERATION` 任务。聊天 Agent 没有上述写权限，用户必须显式调用 API。

### 工单建议约束

- 建议由 `NO_RELIABLE_KNOWLEDGE` 产生，24 小时有效。
- `suggestionId` 绑定 `conversationId`、`sourceTurnId`、会话版本和冻结上下文。
- 同一建议最多生成一个草稿，重复消费返回首次工单。
- `RETRIEVAL_FAILED` 不产生建议。
- 会话后续消息不改变已经冻结的建议上下文。
- 会话过期或建议失效后，只能使用手工创建草稿接口。

## 5. 托管文档 API

| 方法与路径 | 用途 | 核心字段与规则 |
|---|---|---|
| `POST /knowledge/documents/text` | 直接文本创建草稿 | `title`：1～160；`content`：最大 1 MiB；`idempotencyKey` |
| `POST /knowledge/documents/files` | 上传单个 Markdown/TXT | multipart 的可空 `title`、`file`、`idempotencyKey`；输入类型和媒体类型由服务端判断 |
| `GET /knowledge/documents/{documentId}` | 查询详情 | 详情返回 `rawContent` 和安全失败原因 |
| `GET /knowledge/documents` | 分页查询 | 可空 `status`、可空 `keyword`、`page`、`size`；列表不返回正文 |
| `PUT /knowledge/documents/{documentId}/draft` | 修改 `DRAFT/FAILED` | `title`、完整 `content`、`version`；重算哈希并清空失败原因 |
| `POST /knowledge/documents/{documentId}/publish` | 发起异步发布 | `version`、`idempotencyKey`；状态变为 `INDEXING` 并创建任务 |
| `POST /knowledge/documents/{documentId}/archive` | 退出检索 | `version`、`archiveReason` 1～500、`idempotencyKey` |
| `DELETE /knowledge/documents/{documentId}` | 软删除从未发布的草稿 | 查询参数 `version`；发布过的文档只能归档 |

`INDEXING` 禁止编辑、重复发布、删除和归档。`PUBLISHED` 不可直接修改；应归档后新建。`ARCHIVED` 和软删除一期不恢复。

## 6. 已解决案例 API

| 方法与路径 | 用途 | 核心字段与规则 |
|---|---|---|
| `GET /resolved-cases` | 分页查询 | 可空 `status`、`sourceTicketNo`、`keyword`、`page`、`size` |
| `GET /resolved-cases/{caseId}` | 查询详情 | 返回来源工单摘要和完整案例内容 |
| `PUT /resolved-cases/{caseId}/draft` | 修改草稿或发布失败内容 | `title`、`problem`、`cause`、`solution`、`version` |
| `POST /resolved-cases/{caseId}/publish` | 人工确认后发布 | `version`、`idempotencyKey`；状态变为 `PUBLISHING` |
| `POST /resolved-cases/{caseId}/reject` | 拒绝案例草稿 | `rejectionReason` 1～500、`version`、`idempotencyKey` |
| `POST /resolved-cases/{caseId}/archive` | 已发布案例退出检索 | `archiveReason` 1～500、`version`、`idempotencyKey` |

列表只返回摘要，详情返回完整 `problem`、`cause`、`solution`。案例发布后不可直接修改。

## 7. 异步任务 API

| 方法与路径 | 用途 | 规则 |
|---|---|---|
| `GET /async-tasks/{taskId}` | 查询任务详情 | 不返回 `lockedBy`、`lockedUntil` 等 Worker 内部字段 |
| `GET /async-tasks` | 分页查询 | 可按 `taskType`、`status`、`aggregateType`、`aggregateId` 过滤 |
| `POST /async-tasks/{taskId}/retry` | 人工重试 `DEAD` 任务 | 请求 `idempotencyKey` 和 `reason`；创建新任务，不复活原任务 |

不提供修改任务状态、删除任务或立即强制执行接口。重试前必须重新检查业务对象当前状态和版本。

## 8. 检索评估 API

仅在 `dev/test` Profile 注册，`prod` 不创建 Controller。

- `POST /retrieval-evaluations`：请求 `mode` 和可空 `caseIds`，返回 `evaluationRunId`。
- `GET /retrieval-evaluations/{evaluationRunId}`：返回运行状态、用例进度、`Recall@5`、`MRR@10`、`nDCG@5`、无命中准确率和精确词召回率。

评估结果只保存在内存，重启后消失；正式报告写到 Maven 的 `target/retrieval-evaluation/`。接口不能修改评估集或自动改变检索参数。

## 9. 主要错误码

| 错误码 | HTTP 状态 | 含义 |
|---|---:|---|
| `COMMON_VALIDATION_FAILED` | 400 | 请求字段缺失、格式错误或超限 |
| `COMMON_CONFLICT` | 409 | 通用状态冲突 |
| `COMMON_IDEMPOTENCY_IN_PROGRESS` | 409 | 同一幂等操作仍在执行 |
| `COMMON_IDEMPOTENCY_KEY_REUSED` | 409 | 同一幂等 Key 被用于不同请求内容 |
| `COMMON_INTERNAL_ERROR` | 500 | 未预期的服务端错误 |
| `CHAT_CONVERSATION_EXPIRED` | 404 | Redis 会话已过期或不存在 |
| `CHAT_VERSION_CONFLICT` | 409 | 会话版本与客户端预期不一致 |
| `CHAT_CONVERSATION_BUSY` | 409 | 同一会话已有运行任务 |
| `CHAT_DUPLICATE_MESSAGE` | 409 | 同一 `clientMessageId` 重复提交且无法复用原结果 |
| `CHAT_MODEL_UNAVAILABLE` | 503 | Chat 模型未配置、超时、熔断或不可用 |
| `CHAT_PROMPT_INJECTION_BLOCKED` | 422 | 当前消息包含无法安全处理的高置信度注入指令 |
| `CHAT_ANSWER_VALIDATION_FAILED` | 502 | 模型答案两次均未通过确定性校验 |
| `CHAT_TICKET_SUGGESTION_EXPIRED` | 410 | 工单建议已经过期 |
| `CHAT_TICKET_SUGGESTION_NOT_FOUND` | 404 | 建议不存在或不属于当前会话 |
| `RETRIEVAL_FAILED` | 503 | BM25 和向量检索均失败 |
| `TICKET_NOT_FOUND` | 404 | 工单不存在 |
| `TICKET_STATUS_CONFLICT` | 409 | 当前工单状态不允许该操作 |
| `TICKET_VERSION_CONFLICT` | 409 | 工单乐观锁冲突 |
| `KNOWLEDGE_NOT_FOUND` | 404 | 文档或案例不存在 |
| `KNOWLEDGE_DUPLICATE_CONTENT` | 409 | 相同内容哈希的未归档知识已存在 |
| `KNOWLEDGE_SENSITIVE_CONTENT` | 422 | 内容含疑似凭据或敏感信息 |
| `KNOWLEDGE_INDEX_FAILED` | 503 | 知识索引任务最终失败 |
| `ASYNC_TASK_NOT_FOUND` | 404 | 异步任务不存在 |
| `ASYNC_TASK_NOT_RETRYABLE` | 409 | 当前任务或业务对象不允许人工重试 |
| `DASHSCOPE_NOT_CONFIGURED` | 503 | 当前环境未配置 DashScope 密钥 |
| `DEPENDENCY_UNAVAILABLE` | 503 | 必要基础设施暂不可用 |

`INTENT_MODEL_DEGRADED` 和 `RERANK_DEGRADED` 是内部观测码，不作为接口失败返回。

接口不得返回 Java 异常类名、SQL、DashScope 原始错误正文、内部地址、密钥或堆栈。

## 10. IDEA HTTP Client 规划

后续业务实现提供：

```text
http/00-health.http
http/10-knowledge-document.http
http/20-chat.http
http/30-ticket.http
http/40-resolved-case.http
http/50-async-task.http
http/60-retrieval-evaluation.http
```

文件不得包含真实密钥；检索评估文件明确标注仅用于本地开发。

## 11. 三期新增公共接口

### 11.1 认证与账号安全

| 方法与路径 | 含义 | 权限与关键字段 |
|---|---|---|
| `POST /auth/login` | 本地用户名密码登录 | 匿名；`username`、`password`；成功返回一次性原始 Token、失效时间和用户摘要 |
| `POST /auth/logout` | 撤销当前 Token | 已认证；重复注销保持幂等 |
| `GET /users/me` | 查询本人安全摘要 | 已认证；不返回密码或 Token 哈希 |
| `POST /users/me/password` | 修改本人密码 | `oldPassword`、`newPassword`；成功后全部旧 Token 失效 |
| `POST /users/me/tokens/revoke-all` | 撤销本人全部 Token | 已认证；包含当前 Token |
| `GET /admin/users` | 分页查询用户 | `ADMIN`；可按 `role/status` 筛选，`page` 从 1 开始 |
| `PATCH /admin/users/{userId}/role` | 修改角色 | `ADMIN`；`role`、`version`；禁止管理员降低自己的角色 |
| `PATCH /admin/users/{userId}/status` | 启用或禁用账号 | `ADMIN`；禁用立即撤销目标用户全部 Token |
| `POST /admin/users/{userId}/password-reset` | 设置一次性密码 | `ADMIN`；`newPassword`、`version`；设置 `mustChangePassword=true` |
| `POST /admin/users/{userId}/unlock` | 清除账号锁定 | `ADMIN`；`version`；不把 `DISABLED` 改为 `ACTIVE` |
| `POST /admin/users/{userId}/tokens/revoke-all` | 撤销目标用户全部 Token | `ADMIN` |

受限 Token 的 `mustChangePassword=true` 时只允许 `GET /users/me`、`POST /users/me/password` 和 `POST /auth/logout`。

### 11.2 会话与长期记忆

| 方法与路径 | 含义 | 核心约束 |
|---|---|---|
| `GET /conversations` | 查询本人会话列表 | 按最近访问时间倒序，不扫描 Redis 全部键 |
| `GET /conversations/{id}` | 查询会话详情 | 只返回安全摘要元数据和最近成功轮次 |
| `POST /conversations/{id}/reset` | 原子重置会话 | 校验所有者、版本和运行状态；代次递增 |
| `DELETE /conversations/{id}` | 永久删除会话 | 校验所有者、版本和运行状态 |
| `GET/PATCH /users/me/memory-settings` | 查询或修改本人长期记忆开关 | 默认关闭，修改使用乐观锁和幂等键 |
| `GET /memories` | 查询本人长期记忆 | 只返回当前用户的数据 |
| `POST /memories/{memoryId}/confirm` | 确认候选 | `PROPOSED -> ACTIVE`，使用版本和幂等键 |
| `PATCH /memories/{memoryId}` | 更正正文、固定状态或失效时间 | 所有者校验、敏感内容检查和乐观锁 |
| `POST /memories/{memoryId}/revoke` | 撤销注入资格 | 撤销后不再进入模型上下文 |
| `DELETE /memories/{memoryId}` | 永久删除本人记忆 | 删除后不可恢复且不再注入 |

认证失败返回 401，权限不足返回 403，资源不存在返回 404，版本或状态冲突返回 409，登录退避返回 429。所有普通 JSON 响应仍使用 `ApiResult<T>`。

## 12. 阶段 15 Prompt 输入安全语义

- `/chat/stream` 在建立 SSE 和创建会话前检查当前消息。高置信度注入返回 HTTP 422、`ApiResult` 错误码 `CHAT_PROMPT_INJECTION_BLOCKED`，公开消息固定为“请求包含无法安全处理的指令”。
- `ALLOW` 与 `GUARD` 都允许继续处理；`GUARD` 只影响服务端安全边界，不增加客户端字段，也不公开命中的内部信号。
- 检索证据、历史、摘要、长期记忆和工单字段均按不可信数据处理。高风险内容只从本次模型上下文排除，不修改或删除原始数据。
- 可靠证据全部被排除时沿用 `NO_RELIABLE_KNOWLEDGE`，不会向客户端伪装为检索故障。
- 阶段 15 未改变既有 SSE 成功事件顺序；模型输出统一安全网关仍属于阶段 16。

## 13. 阶段 16 模型输出失败语义

- 问候、RAG 和工单回答必须先完整缓冲并通过统一输出安全网关，之后才允许发送 `answer.started`、`answer.delta` 和引用。
- 第一次可修复失败在服务端完整重生成一次，首次正文对客户端不可见；第二次仍失败或随机标记、Prompt、凭据、个人敏感信息等不可恢复泄漏只发送既有 `error` 终结事件。
- 输出安全失败沿用 `CHAT_ANSWER_VALIDATION_FAILED`，不增加公共响应字段，也不向客户端暴露规则编号、随机标记或模型原文。
- 工单草稿和案例草稿在通过相同网关前不得调用业务持久化入口；因此失败不会创建半轮会话、工单草稿或案例草稿。

## 14. 阶段 17 安全运行边界

- 阶段 17 不新增公共 REST、SSE 事件或响应字段，固定安全评测仅通过自动化门禁执行。
- 安全指标只暴露聚合计数和冻结枚举标签，不提供用户、会话、文档、工单、正文、随机标记或错误详情查询接口。
- 输入策略异常发生在会话租约和模型调用前并失败关闭；输出策略异常不得发送 `answer.delta` 或持久化未经检查的模型正文。
