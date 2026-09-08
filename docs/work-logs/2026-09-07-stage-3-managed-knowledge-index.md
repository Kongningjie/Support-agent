# 阶段工作记录：托管知识与 Elasticsearch 索引

## 基本信息

| 字段 | 内容 | 字段含义 |
|---|---|---|
| 日期 | 2026-09-07 | 阶段开始日期 |
| 阶段 | stage-3-managed-knowledge-index | 总实施计划第 3 阶段 |
| 执行者 | Codex | 实际实施、验证与 Review 负责人 |
| 关联计划 | [阶段 3](../implementation-plan/00-phased-implementation-plan.md#7-阶段-3托管知识与-elasticsearch-索引) | 本阶段执行依据 |
| 状态 | 已完成 | 实现、测试、完整 Review 和记录门禁均已通过 |

## 阶段目标与验收标准

- 实现托管文档从安全导入、草稿维护、异步发布到归档删除索引的完整生命周期。
- 实现确定性分块、八类精确技术词提取、DashScope 文档向量和版本化 Elasticsearch 索引。
- 通过单元测试、MySQL 与 Elasticsearch 集成测试、HTTP 主路径验证和完整差异 Review。
- 不实现混合检索、Rerank、Agent、SSE、工单解决或案例闭环。

## 已确认实现边界

- 重复内容在创建、修改和发布前检查；未归档且未删除的托管文档按规范化正文哈希唯一。
- 使用 MySQL 生成列和唯一索引防止并发重复，内部生成列不向 API 暴露。
- 文件标题为空时从去除扩展名后的文件名生成，无有效标题时拒绝请求。
- 创建、修改和发布前均扫描敏感凭据，任何日志不得包含命中原文。
- Elasticsearch 索引和别名在首次索引任务中按需创建；已有结构不兼容或别名指向其他索引时拒绝自动覆盖。
- 文档最终状态与异步任务最终状态在同一 MySQL 事务提交，并受 Worker 租约约束。
- DashScope 文档向量使用官方 Java SDK，每批最多 10 段，通过独立 `EmbeddingModelPort` 隔离。
- 发布和归档响应返回对应异步任务 ID；Elasticsearch 版本与最终 `PUBLISHED` 版本一致。

## 已完成工作

- 提供直接文本和单个 Markdown/TXT 文件的创建、详情、分页、修改、异步发布、归档及草稿软删除 API；创建和动作接口支持幂等，所有对外 ID 使用字符串。
- 实现严格 UTF-8、1 MiB、控制字符、Unicode/换行规范化、SHA-256、扩展名/媒体类型、文件名派生标题和敏感凭据检查。
- 实现 Markdown 标题路径、TXT/直接文本段落感知分块、超长重叠、碎片合并，以及八类精确技术词提取；记录 `chunk-v1` 和 `exact-v1`。
- 新增独立 `EmbeddingModelPort` 和 DashScope 官方 Java SDK 适配：文档每批最多 10 段，单次 10 秒，临时失败最多重试两次，严格校验顺序、数量、1024 维和有限值。
- 实现 Elasticsearch 9.5.2 + ICU 的 `support_knowledge_v1`、`support_knowledge_current`、严格 Mapping、确定性 `_id`、Bulk 逐项校验、版本完整性校验、失败清理和来源删除。
- 实现 `KNOWLEDGE_INDEX`、`KNOWLEDGE_DELETE` Handler；文档与任务终态在同一 MySQL 事务提交，并以 Worker 租约作写入围栏。
- 新增 V2 迁移，用生成列和唯一索引阻止有效文档内容并发重复；补齐分页与重复检测 MyBatis 查询。
- 提供 OpenAPI 注册、IDEA HTTP Client 示例和可复用 Markdown 上传样例。

## 文件与契约变化

- 根 POM 管理 DashScope 官方 SDK；Agent 模块实现 Embedding 适配，Infrastructure 模块增加 Elasticsearch REST5/Jackson 和 Elasticsearch Testcontainers 测试依赖。
- Application 模块新增知识命令/查询、内容策略、分块、精确词、索引端口和两个任务 Handler；异步 Handler 改为返回需与任务终态原子提交的业务动作。
- Infrastructure 模块新增事务性任务收口适配器、知识 Elasticsearch 适配器、文档查询 Mapper 和 Flyway V2。
- Interfaces 模块新增 `/api/v1/knowledge/documents` 七组资源操作，统一沿用 `ApiResult`；发布响应包含 `taskId`，归档响应包含删除任务 `taskId`。
- Bootstrap 模块新增知识装配及 Embedding 模型、物理索引、别名和 multipart 大小配置；`.env.example` 仅增加非敏感变量名及默认模型/索引名。

## 设计偏差与确认

- 按方案追问结果，AgentScope 仍作为后续 Agent 编排框架；阶段 3 的批量 Embedding 使用 DashScope 官方 Java SDK，因为一期要求单批最多 10 条且需独立 `EmbeddingModelPort`。
- Elasticsearch 索引按首次任务惰性创建；别名已指向其他索引时返回不可重试冲突，不自动切换或覆盖。
- 未新增阶段 4 的 BM25/向量混合检索、RRF、Rerank、Agent 或 SSE，也未实现阶段 5 的工单解决和案例闭环。

## Review 记录

- Review 范围：当前阶段全部已修改/新增文件的完整工作区差异、未跟踪文件、依赖方向、公开契约、事务与租约、MySQL/Elasticsearch 并发、安全信息、注释、测试和阶段边界。
- 首轮发现并修复：耗尽最后一次尝试的崩溃任务原先会绕过文档最终失败事务；现改为重新抢占后由统一终态端口原子收口。
- 首轮发现并修复：REST5 客户端会把 404 作为普通响应返回，导致不存在索引被误判为 Mapping 冲突；现显式检查 HTTP 状态并补充真实 Elasticsearch 回归测试。
- 首轮发现并修复：Embedding 排序前未先拒绝空 `textIndex`；Bulk 仅检查总 `errors` 标志。现分别前置结构校验并校验 Bulk 项数、逐项状态和 `error` 字段。
- 首轮发现并修复：`online-test` Profile 尚无可执行在线用例；现新增仅在显式提供 `DASHSCOPE_API_KEY` 时运行的文档/查询向量协议测试，默认不会产生外部调用。
- 复审结论：上述问题均已闭环；未发现越阶段实现、真实密钥、敏感正文日志、调试代码、占位实现或遗留 TODO。`git diff --check` 通过；仅有 Git 提示 Windows 工作区未来可能按配置转换 LF/CRLF，不属于内容错误。

## 测试与验证

- `mvn validate`：通过。
- `mvn test`：通过，共 71 个单元/组件测试，0 失败、0 错误。
- `mvn verify -Pintegration`：通过；除再次执行全部单元测试外，共发现 15 个集成测试，其中 14 个 MySQL、Elasticsearch 和完整应用测试执行通过，1 个真实 DashScope 在线测试因未配置密钥而按设计跳过，0 失败、0 错误。
- 集成测试使用 MySQL 8.4.11 和带 `analysis-icu` 的 Elasticsearch 9.5.2，验证 V2 迁移、并发唯一约束、任务抢占恢复、索引惰性创建、Mapping/别名、Bulk、完整性与归档删除；完整应用测试通过真实 HTTP 验证直接文本草稿主路径和 OpenAPI 注册。
- `mvn verify -Ponline-test`：未执行。当前未获得使用真实 DashScope 密钥和产生在线调用费用的授权；离线 SDK 协议测试已覆盖 10 条分批、顺序恢复、非法响应和两次临时故障重试。

## 风险、限制与未完成项

- 真实 DashScope 服务端联调尚未执行；获得密钥与费用授权后可运行 `mvn verify -Ponline-test` 并按在线测试规范补充结果。
- 测试期间 SpringDoc 3.1.0 在生成 OpenAPI 时记录一条 `JsonSchema.type` 的 Jackson 3 兼容性警告，但文档生成及阶段 3 路径断言通过；后续升级依赖时需复核，不在本阶段擅自变更版本。
- Mockito 在 JDK 21 输出未来禁止动态加载 Agent 的预警，当前不影响测试结果；后续统一测试基础设施升级时处理。

## 下一阶段建议

- 停止在阶段 3。仅在用户明确下达阶段 4 指令后，依据总计划实现 BM25、向量检索、RRF、Rerank、Agent 与 SSE。
