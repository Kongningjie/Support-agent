# 异步、一致性、安全、运维与测试

> 本文保留一期工程基线。阶段 14 完成后的认证、记忆清理和安全审计增量见第 11 节及 [当前系统基线](10-current-system-baseline.md)。

## 1. 持久化异步任务

一期不引入 RocketMQ，使用 MySQL `async_task` 作为持久化工作队列。

任务类型：

- `CASE_GENERATION`：根据已解决工单生成待审核案例草稿。
- `KNOWLEDGE_INDEX`：解析、分块、向量化并写入 Elasticsearch。
- `KNOWLEDGE_DELETE`：删除已归档来源的 Elasticsearch 分块。

状态含义：

- `PENDING`：等待首次执行。
- `RUNNING`：Worker 已取得租约并正在执行。
- `RETRY_WAIT`：临时失败，等待下一次调度。
- `SUCCEEDED`：任务成功完成。
- `DEAD`：达到最大尝试次数或发生不可重试错误。
- `CANCELLED`：任务因业务对象失效而取消。

调度参数：每 2 秒轮询，批量 10，并发 2，锁租约 5 分钟，最多尝试 3 次，退避 30 秒、2 分钟、10 分钟。

Worker 使用 MySQL 8 `FOR UPDATE SKIP LOCKED` 短事务抢占。耗时的模型和 Elasticsearch 调用必须在抢占事务提交后执行；任务执行期间按需续租。

人工重试只允许 `DEAD` 任务，并新建任务、记录 `retryOfTaskId`，不复活原任务。

## 2. 事务与幂等

以下内容必须处于同一个 MySQL 事务：

- 业务状态变更。
- 对应 `async_task` 插入。
- 外部请求幂等记录的成功状态。

外部请求幂等唯一键为 `(operatorId, operationType, idempotencyKey)`。同一 Key 和同一请求重复提交时返回首次资源；同一 Key 携带不同参数时返回冲突。参数 Bean Validation 在占用幂等 Key 之前完成。

聊天消息使用 `clientMessageId` 幂等，不与普通写 API 的 `idempotencyKey` 混用。`async_task.idempotencyKey` 负责内部任务去重，也不与外部请求记录混用。

## 3. Elasticsearch 发布一致性

发布流程：

1. 按 `aggregateId`、`aggregateVersion` 读取 MySQL 来源。
2. 校验状态、版本和 `contentHash`。
3. 确定性分块、提取精确词并生成 Embedding。
4. 使用 Bulk API 写入本次来源版本的全部分块。
5. 逐项检查 Bulk 结果。
6. 按数量和内容哈希验证完整性。
7. 使用 MySQL 短事务把来源改为 `PUBLISHED`，写入发布时间，并把任务置为 `SUCCEEDED`。

部分失败时删除本次版本已写入的分块并重试。最终死亡时，托管文档进入 `FAILED`，案例进入 `PUBLISH_FAILED`，保存脱敏原因。

发布、删除和重试依靠确定性 `_id`、业务状态机、来源版本和任务幂等实现最终一致性。

归档时先在 MySQL 标记 `ARCHIVED`，同时创建 `KNOWLEDGE_DELETE`。即使 Elasticsearch 删除暂时失败，检索结果也需回查当前有效来源，防止归档内容继续回答。

## 4. 内容与提示词安全

- 用户单条消息最大 4000 字符。
- 文档最大 1 MiB，只接受 `.md`、`.markdown`、`.txt` 和直接文本。
- 校验扩展名、媒体类型、UTF-8 解码、空内容、二进制和危险控制字符。
- 不下载 Markdown 中引用的远程图片或资源。
- 发布前扫描 PEM 私钥、JWT、云 API Key、`password=`、含密码的数据库 URL 和 Bearer Token。
- 检测到疑似凭据时拒绝发布，日志不得输出检测到的秘密原文。
- 用户消息和知识证据使用明确分隔并标记为不可信，不能覆盖系统指令或强迫工具调用。
- 输出再次执行敏感信息和精确值校验。

## 5. 配置和密钥

配置文件规划：

```text
application.yml
application-dev.yml
application-test.yml
application-prod.yml
.env.example
```

环境变量：

| 变量 | 含义 |
|---|---|
| `SUPPORT_AGENT_MYSQL_URL` | MySQL JDBC 地址 |
| `SUPPORT_AGENT_MYSQL_USERNAME` | MySQL 用户名 |
| `SUPPORT_AGENT_MYSQL_PASSWORD` | MySQL 密码 |
| `SUPPORT_AGENT_REDIS_URL` | Redis 连接地址 |
| `SUPPORT_AGENT_REDIS_PASSWORD` | Redis 密码，可按环境为空 |
| `SUPPORT_AGENT_ELASTICSEARCH_URL` | Elasticsearch 地址 |
| `SUPPORT_AGENT_ELASTICSEARCH_USERNAME` | Elasticsearch 用户名，可按环境为空 |
| `SUPPORT_AGENT_ELASTICSEARCH_PASSWORD` | Elasticsearch 密码，可按环境为空 |
| `SUPPORT_AGENT_WORKER_ID` | 异步任务 Worker 实例标识；为空时由应用在启动时生成 |
| `DASHSCOPE_API_KEY` | DashScope API 密钥 |

`.env` 必须忽略，仓库只提交 `.env.example`。IDEA 运行配置只引用本地环境变量，不提交真实秘密。

开发环境缺少 DashScope 密钥时允许启动，模型相关 API 返回 `DASHSCOPE_NOT_CONFIGURED`；生产环境缺少密钥时启动失败。

## 6. 健康检查

- `/actuator/health/liveness`：只表示 JVM 和 Spring 应用是否存活，不检查外部依赖。
- `/actuator/health/readiness`：检查 MySQL、Redis、Elasticsearch；DashScope 只检查配置存在性，不发送付费探测请求。
- `/actuator/info`：返回应用名、版本、Git 提交、构建时间和 Profile，不暴露地址或凭据。
- `/actuator/metrics`：仅 `dev/test` 开放；一期生产只开放 `health` 和 `info`。

启动规则：Flyway 失败或 MySQL 不可连接时启动失败；Redis、Elasticsearch 暂不可用时允许进程启动但 readiness 不健康；业务接口按实际依赖返回准确错误。

生产健康详情只返回总体状态，不暴露内部错误。

## 7. 本地基础设施

后续使用 `deploy/compose.yml` 只启动：

- MySQL 8.4.11 LTS。
- Redis 8.8.0 官方普通镜像。
- Elasticsearch 9.5.2，自定义 Dockerfile 安装同版本 ICU Analysis 插件。

应用从 IDEA 或 Maven 在宿主机运行。Compose 使用健康检查、localhost 端口绑定、命名 Volume 和命名 Network，不设置 `container_name`。

一期不加入 Kibana、RedisInsight、Adminer 或 phpMyAdmin。

## 8. 日志与指标

- 使用结构化 JSON 日志，并通过 `runId` 关联一次 Agent 运行。
- MySQL 只保存运行摘要、检索摘要和最多 30 个候选分数。
- 不记录完整 Prompt、完整模型输出、完整知识内容、工具完整结果和隐藏推理。
- 记录模型用途、模型名、耗时、状态和安全错误码。
- 使用 Actuator 与 Micrometer 暴露应用内指标。
- 一期不部署 Jaeger、Prometheus 或 Grafana。

## 9. 测试分层

使用 JUnit 5、Testcontainers、WireMock、ArchUnit 和 JaCoCo。

### `mvn test`

- 快速、离线。
- 不要求 Docker 和 DashScope Key。
- 包含领域状态机、应用编排、幂等、校验、分块、精确词、引用和 ArchUnit 测试。

### `mvn verify -Pintegration`

- 显式要求 Docker。
- 使用 Testcontainers 验证 MySQL、Redis、Elasticsearch 和 ICU。
- 覆盖 Flyway、Mapper、Redis Lua、异步抢占、Bulk 发布与删除。

### `mvn verify -Ponline-test`

- 显式要求真实 DashScope Key。
- 验证 Chat、Embedding、Rerank 和结构化输出的实际兼容性。
- 不属于普通 CI 和 `mvn test` 的通过条件。

JaCoCo 生成报告但一期不设全局硬覆盖率。状态迁移、幂等、引用校验和安全校验关键分支必须完整覆盖。

## 10. 固定检索评估集

在 Bootstrap 测试资源中保存约 50 条 JSONL 人工标注用例：

- 20 条已知知识问题。
- 10 条精确术语问题。
- 10 条同义改写问题。
- 5 条无知识问题。
- 5 条知识冲突问题。

每条字段：

| 字段 | 含义 |
|---|---|
| `caseId` | 评估用例唯一标识 |
| `query` | 用户检索问题 |
| `relevantSourceIds` | 人工标注的相关知识来源 ID |
| `expectedStatus` | 预期三态检索结果 |
| `requiredExactTerms` | 必须召回或保留的精确技术词 |
| `description` | 用例目的和人工判断说明 |

对比 `BM25_ONLY`、`VECTOR_ONLY`、`HYBRID`、`HYBRID_RERANK`，报告 Recall@5、MRR@10、nDCG@5、无命中准确率和精确词召回率。评估只报告，不自动修改线上参数。

## 11. 三期安全与运行增量

- 本地密码使用 BCrypt strength 12；密码长度为 12～72 个字符且 UTF-8 不超过 72 字节。
- Redis 不透明 Bearer Token 固定 TTL 2 小时，每用户最多 5 个有效 Token；服务端和日志不得保存或输出原始 Token。
- 登录失败采用 Redis Lua 原子递增退避；第 3、4、5 次及以上分别锁定 30 秒、2 分钟和 15 分钟，账号锁截止时间在 MySQL 中单调延长。
- `security_event` 与 `support.agent.security.events` 指标只使用稳定事件、结果和原因分类，禁止用户标识或正文进入指标标签。
- 长期记忆候选模型并发上限为单实例全局 4、每用户 1，无等待队列；限流或模型失败不得影响聊天结果。
- 创建满 30 天的 `PROPOSED` 候选每小时批量清理，每批最多 500 条；`ACTIVE` 和 `REVOKED` 不自动删除。
- 当前离线与集成门禁为 `mvn test` 和 `mvn verify -Pintegration`；真实 DashScope 验证仍必须显式授权后运行 `mvn verify -Ponline-test`。

## 12. 阶段 15 LLM 输入安全

- 当前消息、历史、摘要、长期记忆、检索证据和工单自由文本进入模型前统一执行确定性 `ALLOW/GUARD/BLOCK` 判定。
- 判断采用角色伪造、规则覆盖、Prompt 索取、工具越权、编码指令和明确执行语气的组合；单个关键词不直接阻断，合法安全讨论进入 `GUARD`。
- 所有 Prompt 使用转义后的 `<untrusted_data source="...">` 数据区，数据区只提供事实，不得改变系统规则、工具权限或输出格式。
- 高风险上下文仅从本次模型调用排除，不删除 MySQL、Redis 或 Elasticsearch 中的源数据；全部可靠证据被排除时按无可靠知识处理。
- `SUPPORT_AGENT_LLM_SECURITY_ENABLED`：LLM 输入安全总开关，默认 `true`；生产环境不得关闭。
- `SUPPORT_AGENT_LLM_BLOCK_HIGH_CONFIDENCE_INPUT`：是否在创建会话前拒绝高置信度直接注入，默认 `true`；生产环境不得关闭。
- `SUPPORT_AGENT_LLM_EXCLUDE_HIGH_RISK_CONTEXT`：是否排除高风险上下文项，默认 `true`；生产环境不得关闭。
- 阶段 15 不记录原始命中正文，也不实现阶段 16 的输出泄漏标记和统一输出安全网关。

## 13. 阶段 16 输出安全运行配置

- `support-agent.security.llm.prompt-canary-enabled` 默认 `true`：为每次受保护生成调用创建独立随机标记；标记只存在于调用内存和系统 Prompt，不得进入日志、指标、审计、数据库或客户端。
- `support-agent.security.llm.maximum-regenerations` 默认 `1`：只允许第一次可修复输出失败后完整重生成一次。生产环境必须保持随机标记开启且重生成次数为 `1`，否则启动失败。
- 输出规则只返回冻结枚举编号，不记录失败正文。阶段 16 不增加安全指标；低基数观测属于阶段 17。
- 本地覆盖使用 `SUPPORT_AGENT_LLM_PROMPT_CANARY_ENABLED` 和 `SUPPORT_AGENT_LLM_MAXIMUM_REGENERATIONS`；不得通过公共 API 动态修改。

## 14. 阶段 17 安全评测与观测

- 固定数据集位于 `support-agent-infrastructure/src/main/resources/evaluation/llm-security-cases.jsonl`，字段 `caseId/category/source/input/expectedAction/requiredSignals/forbiddenSignals/notes` 分别表示稳定编号、样本类别、生产来源、测试正文、预期动作、必须命中的规则、禁止命中的规则和人工说明。数据集不含真实凭据或个人信息。
- 数据集固定为直接注入 20、间接注入 20、输出泄漏或危险内容 15、困难正常样本 25；`mvn test` 自动执行生产确定性策略并生成不含正文的 `SecurityEvaluationReport`。
- 指标前缀为 `support.agent.security`，记录 Prompt 评估、上下文排除、输出评估、完整重生成和最终拒绝。标签只允许 `source/action/signal/branch/rule`，未知输出规则折叠为固定 `NONE`，禁止业务标识、正文、随机标记和错误详情。
- 安全策略异常执行失败关闭：输入预检异常不创建会话或调用模型；输出策略异常不外发、不提交会话且不持久化业务草稿。安全策略不参与 readiness，生产安全配置非法仍按既有规则启动失败。
- 本阶段未运行 `mvn verify -Ponline-test`。固定数据集结果只证明本地策略与应用链路；真实 DashScope 对抗表现必须取得单独授权后验证。
