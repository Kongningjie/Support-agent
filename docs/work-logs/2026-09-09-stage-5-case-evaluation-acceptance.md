# 阶段工作记录：案例闭环、检索评测与一期验收

## 基本信息

| 字段 | 内容 | 字段含义 |
|---|---|---|
| 日期 | 2026-09-09 | 本阶段完成日期 |
| 阶段 | stage-5-case-evaluation-acceptance | 总实施计划第 5 阶段 |
| 执行者 | Codex | 实际实施、验证与 Review 负责人 |
| 关联计划 | [阶段 5](../implementation-plan/00-phased-implementation-plan.md#9-阶段-5案例闭环评估与一期验收) | 本阶段执行依据 |
| 状态 | 已完成 | 单元、集成及真实 DashScope 在线门禁全部通过 |

## 阶段目标与验收标准

- 打通 `OPEN -> RESOLVED -> 案例草稿 -> 人工审核 -> 发布/拒绝/归档 -> RAG` 闭环。
- 使用唯一固定中文数据集生成四种检索模式和五项指标报告，不自动修改参数。
- 完善阶段 5 的 OpenAPI、HTTP Client、README、测试和运维说明。
- 不引入公开数据集、前端、认证、多租户、RocketMQ、自动调参或下一阶段功能。

## 已确认实现边界

- `cause` 和 `solution` 必须逐字复制工单人工结论；AI 只整理 `title` 和 `problem`，且只能读取工单标题、问题描述和已尝试操作。
- 案例生成最终失败时工单保持 `RESOLVED`，任务进入 `DEAD`，通过既有人工重试接口处理；不创建残缺案例。
- 人工可修改案例四个正文，但不回写工单；`REJECTED` 为不可恢复终态。
- 生产聊天默认 `HYBRID_RERANK`；评测支持 `BM25_ONLY`、`VECTOR_ONLY`、`HYBRID`、`HYBRID_RERANK`，只报告、不设质量硬阈值。
- 一期只使用自编固定中文测试数据；脱敏真实工单评测集留待上线后建设。

## 已完成工作

- 新增解决工单接口，并在同一幂等事务写入解决状态与 `CASE_GENERATION` 任务。
- 新增 DashScope 严格 JSON Schema 案例生成；校验字段数量、长度及新增精确事实，根因和方案由服务端复制。
- 完成案例详情、分页、人工编辑、发布、拒绝、归档接口及完整状态规则。
- 复用知识分块、Embedding、版本索引和删除机制发布案例；来源同分时托管文档优先，回答 Prompt 负责冲突披露。
- 新增 15 条固定中文语料和 50 条固定问题，覆盖 20 条已知知识、10 条精确词、10 条同义改写、5 条无知识、5 条冲突。
- 新增后台评测、四种模式、Recall@5、MRR@10、nDCG@5、无命中准确率、精确词召回率及 `target/retrieval-evaluation/` 报告。
- 更新模型配置、中文 README、OpenAPI 中文字段说明及 `http/` 主流程示例。

## 文件与契约变化

| 路径或对象 | 变化类型 | 变化内容及含义 |
|---|---|---|
| `support-agent-domain` | 修改 | 补齐案例草稿创建、人工修改和状态迁移规则 |
| `support-agent-application` | 新增/修改 | 案例用例与任务处理器、四模式检索排名、固定评测及指标计算 |
| `support-agent-agent` | 新增/修改 | 案例结构化 Prompt、严格 Schema 输出、来源冲突规则及 AgentScope Base URL 规范化 |
| `support-agent-infrastructure` | 新增/修改 | 案例分页 Mapper、来源唯一约束、评测数据读取和报告写出 |
| `support-agent-interfaces` | 新增/修改 | 工单解决、案例管理和仅 `dev/test` 可用的评测 API |
| `support-agent-bootstrap` | 新增/修改 | 案例与评测装配、50 条问题、15 条语料和独立意图模型配置 |
| `README.md`、`http/` | 修改/新增 | 启动配置、状态机、SSE、评测、清理策略及可执行请求示例 |

## 设计偏差与确认

| 偏差 | 原因 | 确认情况 | 影响 |
|---|---|---|---|
| 不引入公开数据集 | 一期需要稳定、可解释、无授权风险的验收基准 | 用户于本阶段明确确认 | 当前只衡量固定技术支持语料；上线后另建脱敏业务评测集 |
| 评测运行状态仅保存在内存 | 一期不建设在线评测平台 | 与阶段范围一致 | 应用重启后运行查询失效，但 JSON 报告仍保留在 `target/` |

## Review 记录

| 字段 | 内容 | 字段含义 |
|---|---|---|
| Review 时间 | 2026-09-09 11:56 | 完整差异最后一次检查时间 |
| Review 范围 | 全部已跟踪差异、未跟踪文件、模块依赖、API、状态机、事务、异步路由、索引、评测、配置、测试和文档 | 实际检查边界 |
| Review 结论 | 通过 | 当前差异满足阶段 5 提交前质量门禁，但本任务未提交 |
| 发现问题 | 旧 OpenAPI 门禁仍拒绝解决接口；拒绝/归档共用含糊 `reason`；来源优先级错误覆盖相关性；多 Handler 路由可能歧义；缺少字段说明、事务回滚及关键失败回归测试；AgentScope 对已含 `/api/v1` 的 Base URL 再次拼接固定路径 | Review 实际发现项 |
| 处理结果 | 已更新阶段接口断言；拆分为 `rejectionReason`/`archiveReason`；改为同分时托管文档优先；歧义路由直接失败；补齐字段 Javadoc 和回归测试；统一移除传给 AgentScope Chat/Intent 的 `/api/v1` 后缀并重新验证 | 所有问题已修复并复查 |

## 测试与验证

| 命令或检查 | 结果 | 含义与备注 |
|---|---|---|
| `mvn test` | 通过 | 102 项单元、架构、契约和固定数据集测试，0 失败、0 错误、0 跳过 |
| `mvn verify -Pintegration` | 通过 | 14 项基础设施 IT 与 5 项完整应用 IT 通过；验证 MySQL 8.4.11、Redis 7.4.7、Elasticsearch 9.5.2、Flyway V1–V4、事务回滚和 OpenAPI |
| 首次集成尝试 | 失败后修复 | 先发现旧阶段 OpenAPI 断言；另一次因 Docker Desktop 未启动而未进入业务断言，启动 Docker 后完整重跑通过 |
| `mvn -pl support-agent-agent -am verify -Ponline-test` | 通过 | 使用 `qwen3.7-flash-2026-07-15`、`text-embedding-v4`、`qwen3-rerank` 完成 2 项 Chat、1 项 Embedding、1 项 Rerank 真实请求，0 失败、0 错误、0 跳过；密钥仅从本地 IDEA Run Configuration 注入进程，未写入受版本管理文件 |
| `git diff --check` | 通过 | 无空白错误；仅提示 Windows 工作区未来可能进行 LF/CRLF 转换 |
| 敏感信息与遗留项扫描 | 通过 | 未发现真实密钥、私钥、硬编码生产密码、调试代码或未处理占位实现 |

## 风险、限制与未完成项

- 当前自编语料需要在空本地库按固定顺序发布，才能使 `MANAGED_DOCUMENT:1..10`、`RESOLVED_CASE:1..5` 与人工标注 ID 对齐；生产环境不会自动装载测试语料。
- 本次只验证已启用的 `qwen3.7-flash-2026-07-15`；未对其他 Chat 模型逐一消耗额度验证。复现命令：`mvn verify -Ponline-test`。
- SpringDoc 仍输出阶段 4 已记录的 Jackson Schema 兼容性警告；接口路径和契约断言通过，本阶段未替换或扩展文档技术栈。

## 下一阶段建议

- 第 5 阶段和一期计划到此停止，不自动提交或推送。等待用户检查本阶段结果并决定是否提交当前变更。
