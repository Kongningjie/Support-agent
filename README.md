# Support Agent

基于 Java 21、Spring Boot 4.1、AgentScope Java 和 DashScope 的企业内部技术支持 Agent。一期覆盖托管知识、混合检索、流式问答、工单和已解决案例闭环。

## 核心流程

```text
知识文档发布 ─┐
              ├─> BM25 + 向量 + RRF + Rerank ─> 带来源回答
工单解决 -> AI 案例草稿 -> 人工审核发布 ┘
```

知识来源只有 `MANAGED_DOCUMENT`（托管文档）和 `RESOLVED_CASE`（已解决案例）。两者冲突时优先采用托管文档并披露差异。AI 不会自动解决工单或自动发布案例。

主要状态流转：

- 工单：`DRAFT -> OPEN -> RESOLVED`，或 `OPEN -> CLOSED`；只有人工填写根因和已验证方案后才能解决。
- 托管文档：`DRAFT -> PUBLISHING -> PUBLISHED -> ARCHIVED`；发布失败进入 `PUBLISH_FAILED`，修改后可重试。
- 已解决案例：`DRAFT -> PUBLISHING -> PUBLISHED -> ARCHIVED`；也可从 `DRAFT` 永久进入 `REJECTED`，发布失败进入 `PUBLISH_FAILED`。

## 模块

| 模块 | 职责 |
|---|---|
| `support-agent-domain` | 纯 Java 聚合、状态机和值对象 |
| `support-agent-application` | 用例、端口、异步任务和检索评测 |
| `support-agent-agent` | AgentScope、DashScope、Prompt 与结构化输出 |
| `support-agent-infrastructure` | MySQL、Redis、Elasticsearch、Flyway 与 Outbox |
| `support-agent-interfaces` | REST、SSE、OpenAPI 和统一响应 |
| `support-agent-bootstrap` | Spring Boot 启动、配置和模块装配 |

## Windows 11 本地运行

要求 PowerShell 7、JDK 21、Maven 3.9+ 和 Docker Desktop。先复制 Compose 变量模板并填写本机 MySQL 密码：

```powershell
Copy-Item .\.env.example .\.env
docker compose -f .\deploy\compose.yml up -d --build
docker compose -f .\deploy\compose.yml ps
mvn -pl support-agent-bootstrap -am package
java -jar .\support-agent-bootstrap\target\support-agent-bootstrap-0.1.0-SNAPSHOT.jar
```

`.env` 只供 Docker Compose 读取，Spring Boot 不会自动加载它。通过 IDEA 启动时，请在 `SupportAgentApplication` 的 Run Configuration 中配置 `DASHSCOPE_API_KEY` 和 `DASHSCOPE_HTTP_BASE_URL`。这些变量只对被启动的应用进程生效，因此 IDEA Terminal 中不一定可见。

## 配置

| 变量 | 含义 |
|---|---|
| `DASHSCOPE_API_KEY` | DashScope 密钥，禁止提交或写入日志 |
| `DASHSCOPE_HTTP_BASE_URL` | 工作空间原生 API 根地址，应以 `/api/v1` 结尾 |
| `DASHSCOPE_CHAT_MODEL` | 回答与案例结构化生成模型 |
| `DASHSCOPE_INTENT_MODEL` | 独立意图识别模型 |
| `DASHSCOPE_EMBEDDING_MODEL` | 文档和查询向量模型 |
| `DASHSCOPE_RERANK_MODEL` | 混合召回重排序模型 |
| `SUPPORT_AGENT_MYSQL_URL` | MySQL JDBC 地址 |
| `SUPPORT_AGENT_REDIS_URL` | Redis 地址 |
| `SUPPORT_AGENT_ELASTICSEARCH_URL` | Elasticsearch 根地址 |

默认模型为 Chat `qwen3.8-flash`、Intent `qwen3.7-flash`、Embedding `text-embedding-v4`、Rerank `qwen3-rerank`。

## 验证与接口

```powershell
mvn validate
mvn test
mvn verify -Pintegration
mvn verify -Ponline-test
```

`online-test` 会调用真实 DashScope，必须取得明确授权并在当前 Maven 进程中提供密钥。接口文档启动后访问 `http://localhost:8080/swagger-ui.html`；完整调用示例见 `http/`。

健康检查：

- `/actuator/health/liveness`：只判断应用进程存活。
- `/actuator/health/readiness`：检查 MySQL、Redis、Elasticsearch及 DashScope 配置。
- `/actuator/info`：返回非敏感构建信息。

聊天接口 `POST /api/v1/chat/stream` 使用 SSE。服务端在检索和生成期间发送进度事件或注释心跳；模型原始 delta 不会直接下发。完整答案通过引用与精确值校验后，才发送 `answer.started -> answer.delta -> answer.completed`；两次生成均未通过时只发送 `error`。各分支请求样例和事件顺序见 `http/20-chat.http`。

## 固定检索评测

仓库内保存 15 条自编中文知识语料和 50 条固定问题，覆盖已知知识、精确术语、同义改写、无知识和冲突。`dev/test` 环境可通过 `/api/v1/retrieval-evaluations` 对比 `BM25_ONLY`、`VECTOR_ONLY`、`HYBRID`、`HYBRID_RERANK`。结果只保存在内存，报告写入 `target/retrieval-evaluation/`，不会自动修改检索参数。

评测语料位于 `support-agent-bootstrap/src/main/resources/evaluation/retrieval-corpus.jsonl`，问题位于同目录的 `retrieval-cases.jsonl`。其中 `MANAGED_DOCUMENT:1..10` 和 `RESOLVED_CASE:1..5` 是稳定测试标识。端到端评测前，应在空的本地测试库中按语料顺序导入并发布对应来源；生产环境不加载这些数据。

## 数据清理

应用不提供业务数据物理删除接口：工单保留审计记录，已发布知识只能归档。停止基础设施不会删除数据：

```powershell
docker compose -f .\deploy\compose.yml down
```

删除 Docker Volume 会永久清空本地 MySQL、Redis 和 Elasticsearch 数据，必须先确认目标环境并由操作者显式执行，项目文档不提供自动清库命令。

## 安全边界

禁止提交密钥、`.env`、生产数据、完整 Prompt 或模型完整输出。测试数据必须自编或脱敏；一期不引入公开数据集、消息队列、认证、多租户、前端和自动调参。
