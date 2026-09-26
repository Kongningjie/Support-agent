# Support Agent

基于 Java 21、Spring Boot 4.1、AgentScope Java 和 DashScope 的企业内部技术支持 Agent。当前已完成一期阶段 0～5、二期阶段 6～9、三期阶段 10～14（含阶段 13 补强）、四期阶段 15～17 和前端阶段 F1～F2，覆盖知识检索与工单闭环、上下文压缩、本地认证、会话生命周期、用户可控长期记忆、账号安全、LLM 输入与输出安全治理，以及前端认证、聊天、会话和记忆治理。

当前实现的完整事实基线见 [当前系统基线](docs/implementation-plan/10-current-system-baseline.md)，历史阶段计划只用于解释当时的范围和决策。[四期 LLM 安全方案](docs/implementation-plan/11-phase-4-llm-security-plan.md)的阶段 15～17 已完成规定离线与集成门禁；真实 DashScope 对抗验证仍需单独授权，不能把固定数据集结果表述为在线模型安全率。

## 核心流程

```text
知识文档发布 ─┐
              ├─> BM25 + 向量 + RRF + Rerank ─> 带来源回答
工单解决 -> AI 案例草稿 -> 人工审核发布 ┘

本地账号 -> Bearer Token -> 用户资源归属 -> 会话滚动摘要
                                    └-> 用户确认的跨会话长期记忆

不可信输入/上下文 -> Prompt 安全策略 -> 模型生成 -> 输出安全网关 -> 安全 SSE/业务草稿
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
| `support-agent-application` | 用例、端口、认证、会话与记忆编排、异步任务和评测 |
| `support-agent-agent` | AgentScope、DashScope、Prompt 与结构化输出 |
| `support-agent-infrastructure` | MySQL、Redis、Elasticsearch、Flyway、Outbox、认证与安全审计适配 |
| `support-agent-interfaces` | REST、SSE、Spring Security、OpenAPI 和统一响应 |
| `support-agent-bootstrap` | Spring Boot 启动、配置和模块装配 |
| `support-agent-web` | Vue 3 独立前端；当前提供认证、账号安全、角色守卫和应用外壳 |

## Windows 11 本地运行

要求 PowerShell 7、JDK 21、Maven 3.9+ 和 Docker Desktop。先复制 Compose 变量模板并填写本机 MySQL 密码：

```powershell
Copy-Item .\.env.example .\.env
docker compose -f .\deploy\compose.yml up -d --build
docker compose -f .\deploy\compose.yml ps
mvn -pl support-agent-bootstrap -am package
java -jar .\support-agent-bootstrap\target\support-agent-bootstrap-0.1.0-SNAPSHOT.jar
```

后端启动后，在另一个 PowerShell 窗口启动前端：

```powershell
Set-Location .\support-agent-web
npm ci
npm run dev
```

浏览器访问 `http://localhost:5173`。当前已提供认证、聊天、会话和长期记忆页面；工单与管理员治理页面将在 F3～F4 分阶段实现。

`.env` 只供 Docker Compose 读取，Spring Boot 不会自动加载它。通过 IDEA 启动时，请在 `SupportAgentApplication` 的 Run Configuration 中配置 `DASHSCOPE_API_KEY` 和 `DASHSCOPE_HTTP_BASE_URL`。这些变量只对被启动的应用进程生效，因此 IDEA Terminal 中不一定可见。

## 配置

| 变量 | 含义 |
|---|---|
| `DASHSCOPE_API_KEY` | DashScope 密钥，禁止提交或写入日志 |
| `DASHSCOPE_HTTP_BASE_URL` | 工作空间原生 API 根地址，应以 `/api/v1` 结尾 |
| `SUPPORT_AGENT_CHAT_MODEL` | 回答与案例结构化生成模型 |
| `SUPPORT_AGENT_INTENT_MODEL` | 独立意图识别模型 |
| `SUPPORT_AGENT_SUMMARY_MODEL` | 会话滚动摘要模型，当前为 `qwen3.7-flash` |
| `SUPPORT_AGENT_MEMORY_MODEL` | 长期记忆候选生成模型，当前为 `qwen3.7-flash` |
| `SUPPORT_AGENT_EMBEDDING_MODEL` | 文档和查询向量模型 |
| `SUPPORT_AGENT_RERANK_MODEL` | 混合召回重排序模型 |
| `SUPPORT_AGENT_AUTH_TOKEN_TTL` | 不透明 Bearer Token 固定有效期，默认 2 小时 |
| `SUPPORT_AGENT_AUTH_MAXIMUM_ACTIVE_TOKENS` | 每用户同时有效 Token 上限，默认 5 |
| `SUPPORT_AGENT_BOOTSTRAP_ADMIN_*` | 用户表为空时可选的一次性初始管理员引导配置 |
| `SUPPORT_AGENT_LLM_SECURITY_ENABLED` | LLM 输入与上下文安全总开关，生产环境不得关闭 |
| `SUPPORT_AGENT_LLM_BLOCK_HIGH_CONFIDENCE_INPUT` | 是否在创建会话前拒绝高置信度直接注入，默认开启 |
| `SUPPORT_AGENT_LLM_EXCLUDE_HIGH_RISK_CONTEXT` | 是否从本次模型调用排除高风险上下文，默认开启 |
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

除登录和健康检查外，业务接口均要求 Redis 不透明 Bearer Token。普通用户只能访问自己的会话、工单和长期记忆；管理员负责用户、知识、案例和异步任务治理。账号安全示例见 `http/27-account-security.http`。

## 固定检索评测

仓库内保存 15 条自编中文知识语料和 50 条固定问题，覆盖已知知识、精确术语、同义改写、无知识和冲突。`dev/test` 环境可通过 `/api/v1/retrieval-evaluations` 对比 `BM25_ONLY`、`VECTOR_ONLY`、`HYBRID`、`HYBRID_RERANK`。结果只保存在内存，报告写入 `target/retrieval-evaluation/`，不会自动修改检索参数。

评测语料位于 `support-agent-bootstrap/src/main/resources/evaluation/retrieval-corpus.jsonl`，问题位于同目录的 `retrieval-cases.jsonl`。其中 `MANAGED_DOCUMENT:1..10` 和 `RESOLVED_CASE:1..5` 是稳定测试标识。端到端评测前，应在空的本地测试库中按语料顺序导入并发布对应来源；生产环境不加载这些数据。

## 固定 LLM 安全评测

仓库内另有 80 条自编中文安全样本，覆盖直接注入、知识或上下文中的间接注入、输出泄漏或危险内容以及困难正常样本。`mvn test` 会复用生产确定性策略校验预期动作和安全信号；当前固定集结果为直接注入阻断率 100%、间接注入上下文逃逸 0、危险输出逃逸 0、困难正常样本误阻断率 0。

安全数据位于 `support-agent-infrastructure/src/main/resources/evaluation/llm-security-cases.jsonl`。该结果只验证当前固定样本、确定性规则和应用失败关闭链路，不代表真实模型已经通过开放世界对抗测试。

## 数据清理

工单保留审计记录，已发布知识只能归档；用户可以删除自己的会话和长期记忆。未确认的长期记忆候选达到 30 天后按批次清理。停止基础设施不会删除数据：

```powershell
docker compose -f .\deploy\compose.yml down
```

删除 Docker Volume 会永久清空本地 MySQL、Redis 和 Elasticsearch 数据，必须先确认目标环境并由操作者显式执行，项目文档不提供自动清库命令。

## 安全边界

禁止提交密钥、`.env`、生产数据、完整 Prompt 或模型完整输出。测试数据必须自编或脱敏。当前消息与所有模型上下文统一按不可信数据处理：高置信度直接注入在创建会话前拒绝，高风险证据、历史、摘要、记忆和工单字段只从本次调用排除。问候、RAG、工单回答、工单草稿和案例草稿在外发或持久化前统一执行完整输出安全校验，每次受保护生成调用使用仅存活于内存的随机泄漏标记。当前采用本地用户名密码、BCrypt 和 Redis 不透明 Token，不使用 JWT、Refresh Token、真实 OIDC/SSO、复杂 RBAC、多租户、前端、RocketMQ 或自动调参。
