# Support Agent

企业内部技术支持 Agent。当前仓库处于“一期设计冻结、项目骨架建立”阶段，尚未开发业务代码。

## 当前内容

- Maven 单体部署、六模块工程骨架。
- 已确认技术基线与模块边界。
- Java 21、Spring Boot 4.1.x、AgentScope Java 2.0.1 的版本基线。

## 模块

| 模块 | 职责 |
|---|---|
| `support-agent-domain` | 纯 Java 领域模型、状态机和值对象 |
| `support-agent-application` | 用例编排、输入输出端口、事务边界 |
| `support-agent-agent` | AgentScope、DashScope、Prompt 和模型流适配 |
| `support-agent-infrastructure` | MySQL、Redis、Elasticsearch、Flyway 和异步任务实现 |
| `support-agent-interfaces` | REST API、SSE、DTO、异常响应和 OpenAPI |
| `support-agent-bootstrap` | Spring Boot 启动、配置和模块装配 |

## 骨架校验

项目要求 JDK 21：

```shell
mvn test
```

Docker 集成测试和真实 DashScope 测试将在业务实现阶段分别通过以下 Profile 启用：

```shell
mvn verify -Pintegration
mvn verify -Ponline-test
```

## 当前明确未实现

数据库迁移、领域对象、Repository、Agent、检索、接口、Compose 和业务测试均不在本次骨架创建范围内。
