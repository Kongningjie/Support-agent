# Support Agent 一期核心技术支持闭环冻结方案

> 状态：已完成；覆盖实施阶段 0～5。本文从历史实施文档中抽取一期稳定业务与架构决策，不替代阶段记录。

## 1. 一期定位

一期建立企业内部技术支持 Agent 的最小完整闭环：优先使用已审核知识回答问题；没有可靠知识时由用户显式创建工单；人工解决工单后生成待审核案例；案例经人工发布后重新进入检索。

一期冻结“知识可追溯、回答有依据、写操作由人确认、AI 不补造事实”为产品底线。

## 2. 冻结范围

- Java 21、Spring Boot 4.1、Maven 六模块单体，由启动模块统一装配。
- MySQL 保存业务事实、审计、幂等和异步任务；Redis 保存短期会话；Elasticsearch 承担知识检索。
- 托管文档与已解决案例是两类正式知识来源。
- 知识输入支持 Markdown、TXT 和直接文本，经过安全校验、确定性分块、Embedding 和版本化索引后发布。
- 检索采用 BM25 与向量并行召回、RRF 融合、Rerank 和可靠知识门槛。
- 检索结果固定为 `GROUNDED`、`NO_RELIABLE_KNOWLEDGE`、`RETRIEVAL_FAILED` 三态，故障不得伪装成无知识。
- Chat 使用 SSE，模型正文必须经过引用、精确值和敏感信息校验后才能发送。
- 工单写操作由用户显式触发；Agent 只有只读工单查询能力。
- 工单解决后生成案例草稿，案例只有通过人工审核才能发布。
- MySQL `async_task` 承担持久化异步任务，不引入消息队列。

## 3. 架构边界

依赖方向固定为领域层 ← 应用层 ← 适配层，最后由启动模块装配：

- `support-agent-domain`：领域状态和不变量，不依赖框架。
- `support-agent-application`：用例、端口、事务和业务编排。
- `support-agent-agent`：AgentScope、Prompt 和模型适配。
- `support-agent-infrastructure`：MySQL、Redis、Elasticsearch、Flyway 和 Outbox。
- `support-agent-interfaces`：REST、SSE、DTO、校验与错误响应。
- `support-agent-bootstrap`：配置、装配与启动。

## 4. 一期不变量

- 模型不能自动发布文档、自动解决工单或自动发布案例。
- 知识、案例和模型输出必须保留可追溯证据边界。
- 归档或版本失效的来源不得继续作为有效证据。
- 幂等、乐观锁、异步任务与业务状态必须遵守明确的一致性边界。
- 不保存完整 Prompt、隐藏推理、完整模型原始响应或生产敏感数据。
- 未经确认不得新增模块、中间件、公共状态或业务范围。

## 5. 历史实施依据

- 阶段 0～5 的执行细节见 [分阶段实施总计划](../implementation-plan/00-phased-implementation-plan.md)。
- 领域、API、Agent、RAG 和工程契约见 `docs/implementation-plan/01`～`07` 专题文档。
- 当前实现事实以 [当前系统基线](../implementation-plan/10-current-system-baseline.md) 为准。

## 6. 完成结论

阶段 0～5 已完成一期范围及当时门禁。后续期次可以扩展能力，但不得破坏本方案中的事实可追溯、人工确认、检索三态和失败关闭原则。
