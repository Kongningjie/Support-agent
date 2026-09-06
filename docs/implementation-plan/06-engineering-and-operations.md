# 异步、一致性、安全、运维与测试

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
