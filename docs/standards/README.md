# 开发与 AI Coding 强制规范

本目录规定 Support Agent 的强制开发规则。开始任何阶段前，应先阅读本页、相关专项规范、`../implementation-plan/README.md`、`../implementation-plan/10-current-system-baseline.md` 和最近一份 `../work-logs/` 记录。

## 规则优先级

发生冲突时，按以下顺序执行：

1. 用户在当前任务中的明确要求。
2. 已确认并冻结的实施设计。
3. 本目录中的工程规范。
4. 工具默认行为和通用最佳实践。

不得以“更先进”“更通用”或“以后可能需要”为理由绕过已确认设计。影响业务范围、公共契约、架构、模块或技术栈时，必须暂停实现并取得明确确认。

## 规范索引

1. [开发环境与命令规范](01-development-environment.md)
2. [架构与依赖规范](02-architecture-and-dependencies.md)
3. [Java 编码规范](03-java-coding-standard.md)
4. [接口与持久化规范](04-api-and-persistence-standard.md)
5. [测试、Review 与质量门禁](05-testing-and-quality-gates.md)
6. [安全与可观测性规范](06-security-and-observability.md)
7. [AI Coding 工作流](07-ai-coding-workflow.md)

## 强制用语

- “必须”“不得”“严禁”表示不可跳过的规则。
- “应”表示默认要求；偏离时必须说明理由并记录。
- “可”表示在不改变既定边界的前提下允许选择。
