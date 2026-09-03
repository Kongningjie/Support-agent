# 阶段工作记录：工程基础、领域模型与数据基线

## 基本信息

| 字段 | 内容 | 字段含义 |
|---|---|---|
| 日期 | 2026-09-03 | 阶段开始日期 |
| 阶段 | stage-1-foundation | 总实施计划第 1 阶段 |
| 执行者 | Codex | 实际实施与验证负责人 |
| 关联计划 | [阶段 1](../implementation-plan/00-phased-implementation-plan.md#5-阶段-1工程基础领域模型与数据基线) | 本阶段执行依据 |
| 状态 | 已完成 | 交付物、测试、Review 和阶段记录均已通过 |

## 阶段目标与验收标准

- 建立可启动、可迁移、可测试的工程、领域和数据基础。
- 完成领域状态规则、七张表、基础 Repository、Compose、健康检查和架构测试。
- 通过 `mvn test` 与 `mvn verify -Pintegration`，完成 Review 后停止。
- 不实现业务 Controller、模型调用、知识索引、检索、Agent 或 SSE。

## 已完成工作

- 已建立工单、托管文档、已解决案例和异步任务四类不可变领域聚合及状态枚举。
- 已建立统一时间、UUID、固定操作者端口，以及四类聚合 Repository 端口。
- 已建立 `ApiResult<T>`、`PageResult<T>`、全局异常映射和 HTTP `traceId` 基础设施。
- 已建立独立 MyBatis DO、XML Mapper 和四类 Repository 基础实现。
- 已创建七张一期表的 Flyway 初始迁移，逐字段提供中文 `COMMENT`。
- 已创建 MySQL、Redis、Elasticsearch + ICU 的 Compose 与 Elasticsearch Dockerfile。
- 已完成配置绑定、生产 DashScope 密钥校验、健康组和结构化日志配置。
- 已添加领域状态测试、Web 基础测试、ArchUnit 规则和 MySQL 集成测试。
- 已将 MySQL 基线统一锁定为官方镜像 8.4.11，并使用 Testcontainers 2.x API 验证。
- 已配置生产环境仅暴露 `health`、`info`，并关闭开发用 OpenAPI 端点。

## 文件与契约变化

| 路径或对象 | 变化类型 | 变化内容及含义 |
|---|---|---|
| `support-agent-domain` | 新增 | 四类领域聚合、稳定枚举、领域断言和状态测试 |
| `support-agent-application` | 新增 | 时间、UUID、操作者与 Repository 端口及通用错误类型 |
| `support-agent-infrastructure` | 新增 | 七表迁移、独立 DO、MyBatis XML、Repository 适配和迁移集成测试 |
| `support-agent-interfaces` | 新增 | JSON 统一响应、分页响应、全局异常映射和 traceId 过滤器 |
| `support-agent-bootstrap` | 新增/修改 | 配置绑定、环境校验、健康指示器、Profile 配置和架构测试 |
| `deploy/` | 新增 | 三项基础设施的本地 Compose 和 ICU 镜像构建定义 |
| `pom.xml` | 修改 | 增加已批准的 Testcontainers 2.0.5 BOM 和集成测试生命周期 |

## 设计偏差与确认

| 调整项 | 原因 | 确认与结果 |
|---|---|---|
| MySQL 精确版本统一为 8.4.11 | 原冻结版本缺少对应官方镜像，无法完成可重复构建 | 2026-09-03 经用户确认；文档、Compose 和集成测试已全部统一 |

除上述已确认版本调整外，无业务范围、模块边界或技术栈偏差。

## Review 记录

- Review 范围：全部已修改文件、未跟踪文件、模块依赖、领域状态规则、SQL 迁移、配置、Compose、测试、敏感信息和阶段边界。
- 发现并修复：生产 OpenAPI 暴露、开发密码硬编码、Elasticsearch Basic Auth 配置未使用、异步任务执行窗口约束不足、MySQL 布尔字段告警和 Testcontainers 旧包导入。
- 复核结果：未发现业务假接口、空 Repository、后续阶段占位实现、真实密钥、临时调试代码或未处理 Review 问题。
- 修复后已重新执行单元、架构和集成测试，结果全部通过。

## 测试与验证

| 命令或检查 | 结果 | 含义与备注 |
|---|---|---|
| 环境检查 | 通过 | Windows 11、JDK 21.0.12.1、Maven 3.9.11、Docker 29.7.2、Compose 5.4.0 可用 |
| `mvn test` | 通过 | 共执行 36 个测试：22 个领域测试、4 个 Web 基础测试、6 个配置/健康测试、4 个 ArchUnit 测试 |
| `docker compose -f .\deploy\compose.yml config --quiet` | 通过 | Compose 语法、必填密码变量和可覆盖宿主端口有效 |
| Compose 健康检查 | 通过 | MySQL 8.4.11、Redis 8.8.0、Elasticsearch 9.5.2 均为 healthy；ICU 插件已安装，ES 集群为 green |
| `mvn verify -Pintegration` | 通过 | 6 个集成测试通过：4 个 Repository 往返、1 个七表迁移基线、1 个完整应用启动与依赖降级测试 |
| 可执行 Jar 连接 Compose | 通过 | liveness、readiness、info 均返回 HTTP 200；MySQL、Redis、Elasticsearch 与配置检查均为 UP |
| 敏感信息与遗留项扫描 | 通过 | 未发现真实密钥、私钥、硬编码运行密码、阶段外占位实现或未处理 TODO |

## 风险、限制与未完成项

- 本机已有 MySQL 占用 `3306`，本次 Compose 验证通过 `SUPPORT_AGENT_MYSQL_PORT=13307` 映射宿主端口；默认端口配置本身未改变。
- 测试中 Mockito 会提示未来 JDK 将限制动态 Agent 加载；当前 Java 21 测试结果不受影响，后续升级 JDK 或 Mockito 时需复核测试启动参数。
- 阶段 1 不调用真实 DashScope；真实密钥和在线模型测试不在本阶段授权范围内。

## 下一阶段建议

阶段 1 已完成。进入阶段 2 前必须获得用户明确指令，并重新按阶段门禁执行。
