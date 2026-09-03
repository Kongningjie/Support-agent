# 阶段工作记录：项目骨架

## 基本信息

| 字段 | 内容 | 字段含义 |
|---|---|---|
| 日期 | 2026-09-03 | 项目骨架完成日期 |
| 阶段 | project-skeleton | Maven 多模块骨架建立阶段 |
| 执行者 | Codex | 实际执行者 |
| 关联计划 | [一期设计文档](../implementation-plan/README.md) | 骨架所依据的已确认设计 |
| 状态 | 已完成，存在环境验证限制 | 骨架已建立，但未在 JDK 21 环境完成完整测试 |

## 阶段目标与范围

建立可继续开发的 Maven 六模块单体骨架，不开发数据库迁移、领域模型、Repository、Agent、检索、接口或业务测试。

## 已完成工作

- 创建父级 Maven Reactor 及 `domain`、`application`、`agent`、`infrastructure`、`interfaces`、`bootstrap` 六个模块。
- 固定 Java 21、Spring Boot 4.1.1、AgentScope 2.0.1 等一期版本基线。
- 建立模块依赖、最小启动类、环境配置占位、README、部署及 HTTP 示例目录。
- 配置远程仓库并统一使用 `main` 分支；骨架提交为 `5f79a3d chore: scaffold support agent project`。
- 骨架阶段曾按当时要求将 `docs/` 加入 `.gitignore`；后续文档治理阶段已根据新确认决策改为纳入版本管理。

## 文件与契约变化

| 路径或对象 | 变化类型 | 变化内容及含义 |
|---|---|---|
| `pom.xml` | 新增 | 定义父工程、六模块、Java 及依赖版本基线 |
| `support-agent-*/pom.xml` | 新增 | 定义模块职责所需依赖和依赖方向 |
| `support-agent-bootstrap` | 新增 | 提供 Spring Boot 启动入口和分环境配置占位 |
| `.env.example` | 新增 | 仅描述环境变量名称，不保存真实密钥 |
| `.gitignore` | 修改 | 忽略构建产物、IDE 文件、敏感环境文件、日志和 `docs/` |

本阶段未新增公共 API、数据库字段、状态、错误码或业务契约。

## 设计偏差与确认

无已知设计偏差。骨架严格限制在已确认模块和技术基线内。

## Review 记录

新的“提交前强制 Review”规则在骨架提交后才确认，因此不能追溯声明该提交满足现行 Review 门禁。后续提交必须完全遵守新规则。

## 测试与验证

| 命令或检查 | 结果 | 含义与备注 |
|---|---|---|
| `mvn validate` | 通过 | 父子 POM 和 Maven Reactor 结构有效 |
| `mvn dependency:go-offline -DskipTests` | 通过 | 已声明依赖能够解析下载 |
| `mvn test` | 失败（环境限制） | 本机仅安装 JDK 17，编译器不支持目标版本 21；不代表业务测试失败 |

## 风险、限制与未完成项

- 需要在 JDK 21 环境重新执行 `mvn test`。
- 业务代码、迁移脚本、集成测试和在线模型测试均未开始，符合本阶段范围。

## 下一阶段建议

先完成文档治理和强制规范，再按照实施计划拆分首个业务开发阶段。
