# Support Agent 设计文档

状态：一期阶段 0～5、二期阶段 6～9、三期阶段 10～17 及阶段 13 补强批次均已完成规定离线与集成门禁。四期阶段 18 已完成并通过门禁，阶段 19～20 待执行。轻量级学习部署已经延期，不归属任何产品期次或实施阶段。阶段 10 真实在线摘要评测仍按用户决定暂缓；阶段 17 真实模型对抗验证尚未获得单独授权。

本文档集是后续实现、评审和验收的依据。若实现与文档冲突，应先记录设计变更，再修改代码。

各产品期次已经确认的稳定业务与架构决策，独立保存在 [冻结方案目录](../design-freezes/README.md)。冻结方案不等同于实施计划，也不能直接作为编码指令；尚未实施的期次必须另行形成阶段、迁移顺序和质量门禁明确的实施计划。

## 文档索引

1. [分阶段实施总计划（保留一期细节与后续执行入口）](00-phased-implementation-plan.md)
2. [产品范围与业务闭环](01-product-scope.md)
3. [系统架构与模块边界](02-architecture.md)
4. [领域模型与字段字典](03-domain-and-data-model.md)
5. [API、统一响应与 SSE 契约](04-api-and-sse.md)
6. [Agent、模型与 RAG 设计](05-agent-and-rag.md)
7. [异步、一致性、安全、运维与测试](06-engineering-and-operations.md)
8. [一期验收与项目骨架历史快照](07-delivery-scope.md)
9. [二期优化实施计划（阶段 6～9 执行入口）](08-phase-2-optimization-plan.md)
10. [三期用户与记忆治理实施计划（阶段 10～14 执行入口）](09-phase-3-conversation-memory-plan.md)
11. [当前系统基线（阶段 17 完成后的现状入口）](10-current-system-baseline.md)
12. [三期 LLM 安全补强实施计划（阶段 15～17 历史执行入口）](11-phase-3-llm-security-plan.md)
13. [四期知识空间实施计划（阶段 18 已完成，阶段 19～20 待执行）](12-phase-4-knowledge-space-plan.md)

后续业务开发必须以总计划为执行入口，一次只执行一个阶段；专题文档提供该阶段所需的精确字段和契约。

独立前端计划见 [Support Agent 前端文档](../frontend/README.md)。该方案已冻结，F1 已完成，F2～F5 尚未执行；前端阶段不改变本目录中的后端业务契约。

产品一期至四期的稳定决策见 [冻结方案目录](../design-freezes/README.md)。[四期知识空间冻结方案](../design-freezes/04-phase-4-knowledge-space.md) 已拆分为阶段 18～20 的实施计划；阶段 18 已实施，阶段 19～20 尚未执行。

轻量级学习部署已经延期，其无期次、无阶段号草案也保存在 [冻结方案目录](../design-freezes/README.md)，不属于本实施计划目录的当前执行入口。

## 文档时效与解释规则

- [当前系统基线](10-current-system-baseline.md)、根目录 `README.md` 和各模块 `README.md` 描述当前已实现状态。
- 一至三期已经完成；四期阶段 18 已完成，阶段 19～20 待执行。历史计划中的“暂不实现”“当前没有”等表述只在对应阶段有效。
- `docs/work-logs/` 是不可回写历史的实施证据。后续能力完成后，不修改旧记录中的当时结论，而由新记录和当前基线承接。
- 专题文档同时包含一期基线与后续增量时，以编号更后的增量章节和当前系统基线为准。

## 决策摘要

- 单体部署，Maven 六模块，Java 21。
- Spring Boot 4.1.1。
- 使用 AgentScope Java 2.0.1，只采用 `ReActAgent`。
- DashScope 统一提供 Chat、Intent、Summary、Memory、Embedding、Rerank，但能力端口相互独立。
- MySQL 8.4.11 LTS、Redis 8.8.0、Elasticsearch 9.5.2 + ICU 9.5.2。
- 一期必须支持 BM25、向量混合检索、RRF 和 Rerank。
- 一期不引入 RocketMQ，使用 MySQL `async_task` 持久化工作队列。
- 普通接口使用 `ApiResult<T>`，SSE 使用独立事件协议。
- 当前已经具备本地用户认证、资源归属、会话生命周期、滚动摘要、用户可控长期记忆与账号安全；仍不引入前端、多租户、复杂 RBAC、MCP Server、OAuth 授权服务器或真实 OIDC/SSO。
- 三期安全补强阶段 15～17 已补齐直接与间接 Prompt 注入的输入信任边界、统一模型输出安全网关、固定中文安全评测和低基数安全指标；真实模型对抗验证仍须单独授权后执行，不得把离线门禁结果扩大为线上安全保证。
