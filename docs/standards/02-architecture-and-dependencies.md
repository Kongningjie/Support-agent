# 架构与依赖规范

## 模块边界

| 模块 | 允许职责 | 禁止事项 |
|---|---|---|
| `domain` | 领域对象、值对象、状态机、领域规则 | 依赖 Spring、数据库、缓存、搜索或模型 SDK |
| `application` | 用例编排、输入输出端口、事务边界 | 直接实现具体中间件或暴露 Web DTO |
| `agent` | AgentScope、DashScope、Prompt、Chat/Embedding/Rerank 适配 | 承担数据库实现或接口控制器职责 |
| `infrastructure` | MyBatis、MySQL、Redis、Elasticsearch、Flyway、Outbox | 定义业务用例或直接服务 Controller |
| `interfaces` | REST、SSE、DTO、校验、异常映射、OpenAPI | 直接调用 Mapper、Redis、Elasticsearch 或模型 SDK |
| `bootstrap` | 启动、配置、Bean 装配 | 承载业务规则 |

依赖必须保持 `domain ← application ← adapters ← bootstrap`，不得出现循环依赖。领域对象、接口 DTO、数据库 DO 和 Elasticsearch 文档模型必须分离，不得为了省事复用同一个类。

## 实现约束

- 按业务能力组织包；不得建立无边界的 `common`、`utils` 或“大杂烩”模块。
- 关键业务流程由应用层显式编排。不得用 Spring 事件承载必须成功、必须重试或影响一致性的关键操作。
- 一期使用 MySQL `async_task`/Outbox 机制，不引入 RocketMQ、Kafka 或其他消息队列。
- 不拆微服务，不引入 Spring AI、JPA、MyBatis-Plus，也不得替换已冻结组件。
- 新增第三方依赖前，必须说明用途、替代方案、许可证与维护影响，并获得确认。
- Chat、Embedding、Rerank 必须保持三个独立能力接口，即使一期都由 DashScope 实现。
