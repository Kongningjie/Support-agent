# 开发环境与命令规范

## 固定环境

| 项目 | 约束 | 含义 |
|---|---|---|
| 操作系统 | Windows 11 | 开发、验证和命令示例的默认平台 |
| 命令行 | PowerShell 7 | 文档和 AI 输出统一使用 PowerShell 语法 |
| Java | JDK 21 | 编译与运行版本，不得降级目标版本 |
| 构建工具 | Maven 3.9+ | 使用 Maven Reactor 管理六模块工程 |
| Spring Boot | 4.1.1 | 一期冻结的应用框架版本 |
| AgentScope | 2.0.1 | Agent 编排框架，仅限 Agent 模块使用 |
| 数据组件 | MySQL 8.4.11、Redis 8.8.0、Elasticsearch 9.5.2 + ICU 9.5.2 | 一期冻结的存储与检索基线 |

## 命令要求

- 命令必须可在 PowerShell 中直接执行，不得输出 `export`、`rm -rf`、Bash 管道或其他仅适用于类 Unix Shell 的写法。
- 查找文本和文件优先使用 `rg`、`rg --files`；文件操作优先使用带 `-LiteralPath` 的 PowerShell Cmdlet。
- 执行递归移动或删除前，必须解析并核对绝对路径位于当前仓库内。
- 默认使用 UTF-8；禁止因本机编码破坏中文、SQL 注释或配置文件。

## Git 与配置

- 后续提交统一使用 `main` 分支；不得擅自创建或切换长期分支。
- 不提交 `.env`、IDE 配置、日志和任何 `target/` 产物；占位变量写入 `.env.example`。
- `docs/` 是代码实现依据，必须纳入 Git 管理；仅 `docs/work-logs/.drafts/` 的临时草稿保持本地忽略。
- 未经用户明确要求，不得自动提交、推送、改写历史或执行破坏性 Git 命令。
