# Support Agent 一期设计文档

状态：需求已确认，等待后续业务实现。

本文档集是后续实现、评审和验收的依据。若实现与文档冲突，应先记录设计变更，再修改代码。

## 文档索引

1. [一期分阶段实施总计划（AI Coding 执行入口）](00-phased-implementation-plan.md)
2. [产品范围与业务闭环](01-product-scope.md)
3. [系统架构与模块边界](02-architecture.md)
4. [领域模型与字段字典](03-domain-and-data-model.md)
5. [API、统一响应与 SSE 契约](04-api-and-sse.md)
6. [Agent、模型与 RAG 设计](05-agent-and-rag.md)
7. [异步、一致性、安全、运维与测试](06-engineering-and-operations.md)
8. [一期验收标准与当前骨架边界](07-delivery-scope.md)

后续业务开发必须以总计划为执行入口，一次只执行一个阶段；专题文档提供该阶段所需的精确字段和契约。

## 决策摘要

- 单体部署，Maven 六模块，Java 21。
- Spring Boot 4.1.x；骨架锁定当前 4.1 系列稳定版本 4.1.1。
- 使用 AgentScope Java 2.0.1，只采用 `ReActAgent`。
- DashScope 统一提供 Chat、Embedding、Rerank，但能力端口相互独立。
- MySQL 8.4.11 LTS、Redis 8.8.0、Elasticsearch 9.5.2 + ICU 9.5.2。
- 一期必须支持 BM25、向量混合检索、RRF 和 Rerank。
- 一期不引入 RocketMQ，使用 MySQL `async_task` 持久化工作队列。
- 普通接口使用 `ApiResult<T>`，SSE 使用独立事件协议。
- 不开发前端、登录权限、多租户、MCP Server、长期记忆和在线评估平台。
