# Repository Guidelines

开始任何实现前，必须先阅读 `docs/implementation-plan/00-phased-implementation-plan.md`、`docs/standards/README.md` 和最近一份阶段工作记录。执行二期阶段 6～9 时，还必须完整阅读 `docs/implementation-plan/08-phase-2-optimization-plan.md`。一次只能执行当前冻结计划中的一个阶段，完成并通过门禁后必须停止，等待用户确认。

## 项目结构与模块职责

本项目是 Java 21、Spring Boot 4.1 的 Maven 多模块单体应用：

- `support-agent-domain`：领域模型与状态规则，不依赖框架。
- `support-agent-application`：用例编排、端口与事务边界。
- `support-agent-agent`：AgentScope、DashScope、Prompt 与模型适配。
- `support-agent-infrastructure`：MySQL、Redis、Elasticsearch、Flyway 与 Outbox。
- `support-agent-interfaces`：REST、SSE、DTO、校验与异常响应。
- `support-agent-bootstrap`：启动、配置与模块装配。

代码、资源、测试分别放入各模块的 `src/main/java`、`src/main/resources`、`src/test/java`。`deploy/` 保存部署材料，`http/` 保存请求示例，`docs/` 保存受版本管理的设计、规范和阶段记录。依赖方向必须为领域层 ← 应用层 ← 适配层，最后由启动模块装配。

## 构建、测试与运行

统一使用 Windows 11、PowerShell 7、JDK 21、Maven 3.9+，不得提供仅适用于 Bash 的命令。

```powershell
mvn validate
mvn test
mvn verify -Pintegration
mvn verify -Ponline-test
mvn -pl support-agent-bootstrap -am package
java -jar .\support-agent-bootstrap\target\support-agent-bootstrap-0.1.0-SNAPSHOT.jar
```

前两条命令分别校验工程和运行单元测试；两个 `verify` Profile 用于基础设施集成测试和真实 DashScope 测试。未经授权不得使用真实密钥运行在线测试。

## 编码风格与命名

文件使用 UTF-8；Java/XML 缩进四个空格，YAML 缩进两个空格。类型、成员、常量分别使用 `UpperCamelCase`、`lowerCamelCase`、`UPPER_SNAKE_CASE`。按业务能力组织包，避免泛化的 `common`、`utils`。优先采用构造器注入、不可变对象、明确异常和 `Instant`。每个手写类和方法（含私有方法）必须有有效 Javadoc，说明职责、约束及必要的参数、返回值和异常。

## 测试规范

使用 JUnit 5；适用时使用 ArchUnit 校验模块边界、WireMock 模拟 HTTP。单元测试命名为 `*Test`，集成测试命名为 `*IT`。覆盖成功、校验失败、异常和关键状态流转；修复缺陷必须增加回归测试。不得隐瞒跳过或未执行的测试。

## 提交与 Pull Request

提交信息使用 Conventional Commits，例如 `feat: add knowledge import`、`fix: handle rerank timeout`，统一提交到 `main`。每次提交前必须 Review 完整差异；未 Review、Review 未通过或问题未处理完毕，严禁提交。修复问题后重新 Review 并运行相关测试，在 `docs/` 阶段记录中写明 Review 范围、结论、处理结果和测试结果。

Pull Request 应说明范围、设计影响、测试、风险和关联事项；接口变化需附请求与响应示例。

## 安全与 AI 编码约束

不得提交密钥、`.env`、生产日志、完整 Prompt、模型完整输出或敏感数据；占位配置写入 `.env.example`。不得绕过测试、削弱断言、遗留临时代码或虚报完成。未经明确确认，不得增加模块、中间件、依赖、公共契约、状态或业务范围。
