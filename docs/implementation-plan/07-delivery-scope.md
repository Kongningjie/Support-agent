# 一期验收标准与当前骨架边界

> 本文是项目创建时的一期/骨架验收快照，不再代表当前实现边界。当前系统已完成阶段 0～14，现状请以 [当前系统基线](10-current-system-baseline.md) 为准。

## 1. 一期最终完成标准

- Maven 六模块项目可通过 `mvn test`。
- Compose 能启动 MySQL、Redis 和带 ICU 的 Elasticsearch。
- Flyway 能从空数据库创建全部表、索引、约束和中文字段注释。
- 应用可从 IDEA 启动，健康检查符合设计。
- Markdown、TXT、直接文本可以创建草稿、异步发布并进入索引。
- BM25、向量、RRF、Rerank 和可靠知识门槛完整运行。
- Chat SSE 覆盖问候、有引用回答、无知识建议、检索失败、工单查询和越界请求。
- 用户能够从建议创建工单草稿，并提交、解决或关闭工单。
- 解决工单后能够异步生成案例，经人工修改和发布后进入 RAG。
- 文档和案例归档后不再参与检索。
- 幂等、乐观锁、会话版本冲突和异步重试有自动测试。
- 未配置 DashScope 时离线测试仍可运行；真实调用只由 `-Ponline-test` 启用。
- 固定评估集能够生成四种检索模式的对比报告。
- OpenAPI 中每个字段具有简体中文含义、可空性、限制和示例。
- IDEA HTTP Client 文件覆盖主要业务路径。
- 中文 README 完整描述启动、配置、状态机、SSE 协议和一期限制。
- 不包含前端、登录权限、多租户、RocketMQ、MCP Server、长期记忆和在线评估平台。

不能用空实现、固定假数据或 `TODO` 冒充主链路完成。因第三方 API 或本机环境无法实测的项目必须在交付说明中列出复现命令。

## 2. 本次只创建项目骨架

本次交付范围严格限制为：

- 本 `docs` 设计文档集。
- Maven 父工程和六个子模块 POM。
- Spring Boot 最小启动入口。
- `application*.yml` 配置文件占位。
- `.gitignore` 和不含真实凭据的 `.env.example`。
- 根 README。

本次不包含：

- 任何领域实体、用例、端口或适配器实现。
- Controller、DTO、SSE 实现和 OpenAPI 注解。
- Flyway SQL、MyBatis Mapper 和 Repository。
- Redis 会话、Lua 脚本和 Agent 状态实现。
- Elasticsearch Mapping、索引代码和 RAG 流水线。
- AgentScope Agent、DashScope 客户端和 Prompt 正文。
- 异步 Worker、Compose 文件、HTTP 请求样例和业务测试。

这些内容等待用户明确要求进入业务开发阶段后再实现。

## 3. 骨架验收方式

- `mvn validate`：检查多模块反应堆、POM 结构和 Profile。
- `mvn dependency:go-offline`：确认依赖坐标可解析。
- `mvn test`：需要本机配置 JDK 21；JDK 17 不能用于验证本项目编译。
- 检查 `git diff`，确认没有修改用户已有 IDEA 文件。
