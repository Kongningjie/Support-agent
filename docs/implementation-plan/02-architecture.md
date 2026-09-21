# 系统架构与模块边界

> 本文保留一期架构基线。阶段 14 完成后的端口、认证、会话和记忆增量统一见 [当前系统基线](10-current-system-baseline.md)。

## 1. 部署形态

采用单体部署的 Maven 多模块结构。所有模块最终装配成一个 Spring Boot 可执行 JAR；模块用于约束代码职责，不形成分布式服务。

基础包名为 `com.lawrence.supportagent`，应用名为 `support-agent`，数据库名为 `support_agent`。

## 2. 技术基线

| 技术 | 版本或选择 | 用途 |
|---|---|---|
| Java | 21 | 编译和运行时基线 |
| Spring Boot | 4.1.x，骨架使用 4.1.1 | Web、配置、Actuator 和应用装配 |
| AgentScope Java | 2.0.1 | `ReActAgent`、流式执行和 Agent 状态接入 |
| MyBatis Starter | 4.1.0 | MySQL 数据访问，XML Mapper |
| MySQL | 8.4.11 LTS | 业务数据、审计和持久化异步任务 |
| Redis | 8.8.0 | 短期会话与 Agent 状态 |
| Elasticsearch | 9.5.2 | BM25、向量和混合检索 |
| ICU Analysis | 9.5.2 | 中英文通用分词与规范化 |
| springdoc-openapi | 3.1.0 | 开发环境接口文档 |

不使用 Spring AI、JPA、MyBatis-Plus、Spring Data Elasticsearch 或 Redis Stack。

## 3. Maven 模块

### `support-agent-domain`

纯 Java 领域层。负责实体、值对象、领域规则和状态迁移，不依赖 Spring、数据库、AgentScope 或 Web 类型。

### `support-agent-application`

负责用例编排、端口定义、事务语义和应用结果。依赖 Domain，不依赖任何具体基础设施 SDK。

### `support-agent-agent`

唯一允许依赖 AgentScope 的模块。负责：

- `ReActAgent` 创建和运行。
- DashScope Chat、Embedding、Rerank 和意图识别适配。
- Prompt 加载、变量校验和版本哈希。
- 原生结构化输出与严格 Schema。
- AgentScope 内部流转换为应用层事件。
- `get_ticket` 工具注册和调用桥接。
- AgentScope Redis 状态序列化适配。

### `support-agent-infrastructure`

负责 MySQL、MyBatis XML Mapper、Flyway、Redis、Elasticsearch、异步任务 Worker 和 Repository 适配。不得依赖 AgentScope。

### `support-agent-interfaces`

负责 REST Controller、SSE、请求响应 DTO、Bean Validation、异常映射和 OpenAPI。不得直接访问 Mapper、Elasticsearch Client 或 AgentScope 类型。

### `support-agent-bootstrap`

唯一可执行模块，负责 Spring Boot 启动、配置绑定和模块装配，不承载业务逻辑。

## 4. 依赖方向

```text
domain <- application <- agent
domain <- application <- infrastructure
application <- interfaces
agent + infrastructure + interfaces <- bootstrap
```

应用层定义以下端口：

- `ChatModelPort`：按业务任务生成问候、知识回答、工单回答、工单草稿和案例草稿。
- `IntentRecognitionPort`：识别意图并生成独立检索问题。
- `EmbeddingModelPort`：分别生成查询向量和文档向量。
- `RerankModelPort`：对候选分块重新排序。
- `ConversationSummaryPort`：生成结构化滚动摘要，不与 Chat 端口复用。
- `UserMemoryCandidatePort`：生成待用户确认的长期记忆候选。
- `AuthenticationPort`：隔离 HTTP 认证与本地 Token 或未来外部身份提供商。
- `ExternalIdentityMappingPort`：定义未来外部主体到本地用户的显式关联边界，本期没有真实适配器。
- Repository 端口：持久化和读取领域对象。
- 搜索端口：隔离 Elasticsearch 查询细节。

端口签名不得暴露 AgentScope、DashScope、MyBatis、Redis 或 Elasticsearch 的 SDK 类型。

## 5. 包组织

模块内部按能力分包：

```text
com.lawrence.supportagent
├─ chat
├─ auth
├─ memory
├─ ticket
├─ knowledge
├─ retrieval
├─ resolvedcase
├─ asynctask
├─ model
├─ observability
└─ sharedkernel
```

`sharedkernel` 只容纳真正跨能力共享且具有稳定语义的类型，不设独立 `common` Maven 模块，不创建 `utils` 或 `types` 杂物包。

DTO、领域对象、数据库 DO 和 Elasticsearch Document 必须分离。ArchUnit 在普通单元测试中锁定模块和包依赖方向。

## 6. 事务与异步边界

一期不引入领域事件总线。关键动作由应用服务显式编排，业务状态更新、`async_task` 插入和幂等结果写入处于同一 MySQL 事务。

Spring `ApplicationEventPublisher` 和 `@TransactionalEventListener` 不承担必须执行的业务动作。日志和指标不要求与业务事务原子一致。
