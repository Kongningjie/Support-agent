# Support Agent 一期分阶段实施总计划

## 1. 文档目的

本文是一期业务开发的总执行入口，用于指导开发人员和 AI Coding 按顺序实现项目。本文负责定义冻结方案、阶段边界、交付物和验收门禁；字段、接口、状态和算法细节由本目录对应专题文档提供，不得脱离专题契约自行推断。

执行任何阶段前必须同时阅读：

1. 本文全文。
2. [开发与 AI Coding 强制规范](../standards/README.md)。
3. 当前阶段列出的专题文档。
4. [最近一份阶段工作记录](../work-logs/README.md)。

## 2. 执行协议

- 一次只允许执行一个阶段。未收到“执行第 N 阶段”的明确指令，不得开始该阶段。
- 收到阶段执行指令后，将该阶段标记为 `进行中`；只有阶段门禁全部通过后才能标记为 `已完成`。
- 当前阶段未通过验收、Review 和记录门禁时，不得提前实现下一阶段内容。
- 每个阶段开始前先检查 Git 状态，保护用户已有变更，并创建对应阶段工作记录。
- 每个阶段结束后必须停止，报告已完成、已验证、未验证、风险和下一阶段建议，等待用户确认。
- 不得把后续阶段的接口做成空实现、固定假数据或遗留 TODO；尚未进入的功能保持不存在。
- 发现设计冲突或需要新增技术栈、模块、公共字段、状态、接口、依赖及业务流程时，立即暂停并请求确认。
- 每次提交前必须完成完整差异 Review；只有 Review 通过、必要测试通过、工作记录更新后才允许提交。

## 3. 当前进度

| 阶段 | 名称 | 状态 | 阶段结果含义 |
|---:|---|---|---|
| 0 | 项目骨架与设计冻结 | 已完成 | 六模块 Maven 骨架、技术基线和设计文档已建立 |
| 1 | 工程基础、领域模型与数据基线 | 已完成 | 应用具备可启动、可迁移、可测试的领域和数据基础 |
| 2 | 工单、幂等与异步任务核心 | 待执行 | 无 AI 依赖的工单主流程和持久化任务机制可用 |
| 3 | 托管知识与 Elasticsearch 索引 | 待执行 | 文档可安全导入、异步发布、归档并进入版本化索引 |
| 4 | 混合检索、Agent 与 SSE 对话 | 待执行 | BM25、向量、RRF、Rerank 和受控对话主链路可用 |
| 5 | 案例闭环、评估与一期验收 | 待执行 | 工单解决到案例发布闭环及一期质量验收完成 |

阶段状态只能使用：

- `待执行`：尚未获得执行指令。
- `进行中`：已获得指令，但尚未通过阶段门禁。
- `已完成`：交付物、测试、Review 和阶段记录全部通过。
- `受阻`：存在无法在当前权限或环境内消除的外部阻塞，并已如实记录。

## 4. 一期冻结方案

### 4.1 产品和业务边界

一期建设企业内部技术支持 Agent。系统优先根据已发布知识提供带引用回答；检索正常但无可靠知识时，只能建议用户显式创建工单；人工解决工单后，AI 整理待审核案例，人工修改并发布后进入知识库。

核心原则：知识可追溯、回答有依据、写操作由人确认、AI 不补造事实。检索技术故障必须返回故障，不得伪装成“没有知识”，也不得因此建议建单。

一期不实现前端、认证授权、多租户、RocketMQ、MCP Server、多 Agent、长期记忆、自动写操作、复杂工单流程或独立观测平台。完整范围以 [产品范围与业务闭环](01-product-scope.md) 为准。

### 4.2 架构与技术栈

- Windows 11、PowerShell 7、Java 21、Maven 3.9+、Spring Boot 4.1.1。
- 单体部署、六个 Maven 模块，最终生成一个可执行 JAR。
- AgentScope Java 2.0.1，只使用 `ReActAgent`，`maxIters=3`。
- DashScope 统一供应模型，但保留 `ChatModelPort`、`IntentRecognitionPort`、`EmbeddingModelPort`、`RerankModelPort` 等独立端口。
- MySQL 8.4.11 保存业务事实、审计、幂等和异步任务；Redis 8.8.0 保存短期会话与 Agent 状态；Elasticsearch 9.5.2 + ICU 9.5.2 承担知识检索。
- 不使用 Spring AI、JPA、MyBatis-Plus、Spring Data Elasticsearch、Redis Stack 或消息队列。
- 关键异步动作使用 MySQL `async_task`/Outbox，业务状态、任务插入和幂等结果在同一事务中提交。

模块职责、端口及依赖方向以 [系统架构与模块边界](02-architecture.md) 为准。

### 4.3 数据和状态

一期核心持久化对象为 `ticket`、`managed_document`、`resolved_case`、`async_task`、`idempotency_record`、`agent_run`、`retrieval_trace`。MySQL 使用 InnoDB、`utf8mb4`、UTC `DATETIME(6)`；Java 时间使用 `Instant`；内部 ID 对外按字符串返回；状态以稳定英文字符串保存；写操作使用版本号和乐观锁。

Redis 会话空闲 TTL 为 7 天，只保留最近 20 个完整轮次；失败轮次不写入。Elasticsearch 使用 `support_knowledge_v1` 物理索引和 `support_knowledge_current` 别名，初始向量维度为 1024。

所有表、字段、索引、状态、标识符、Redis 和 Elasticsearch 字段的精确含义必须遵循 [领域模型与字段字典](03-domain-and-data-model.md)，不得只按名称猜测。

### 4.4 API 和交互协议

- REST 基础路径为 `/api/v1`。
- 普通 JSON API 使用 `ApiResult<T>`；分页数据使用 `PageResult<T>`；SSE 不包装 `ApiResult`。
- HTTP 状态码保留协议语义；OpenAPI 每个字段必须提供简体中文含义、可空性、限制和示例。
- 一期固定操作者为 `dev-operator`，不信任客户端身份请求头。
- Chat 入口为 `POST /api/v1/chat/stream`，使用独立 SSE 事件协议，不支持断线重放。
- 写操作使用外部 `idempotencyKey`；聊天使用 `clientMessageId`；二者与内部任务幂等键相互独立。

完整接口、字段、SSE 事件和错误码以 [API、统一响应与 SSE 契约](04-api-and-sse.md) 为准。

### 4.5 Agent 与 RAG

DashScope 模型冻结为：Chat `qwen3.7-plus-2026-05-26`、Intent `qwen3.7-flash`、Embedding `text-embedding-v4`、Rerank `qwen3-rerank`，不配置备用供应商或备用模型。

意图仅包含 `GREETING`、`SUPPORT_QUERY`、`TICKET_QUERY`、`OUT_OF_SCOPE`。规则优先，模型低置信度或失败降级为 `SUPPORT_QUERY`。Agent 只有只读 `get_ticket(ticketNo)` 工具，任何写操作必须由用户显式调用 API。

知识输入仅支持 Markdown、TXT 和直接文本。分块采用确定性规则，目标 800、最大 1200 个 Unicode 字符。精确词只提取 `ERROR_CODE`、`VERSION`、`COMMAND`、`CONFIG_KEY`、`FILE_PATH`、`TICKET_NO`、`CLASS_OR_PACKAGE`、`URL_OR_ENDPOINT`。

检索固定流水线：BM25 Top 50 与 Vector Top 50 并行，使用 `RRF(k=60)` 融合为 Top 30，Rerank 后最多选择 Top 5。检索结果为 `GROUNDED`、`NO_RELIABLE_KNOWLEDGE`、`RETRIEVAL_FAILED` 三态。引用、可靠知识门槛、降级和最终回答校验以 [Agent、模型与 RAG 设计](05-agent-and-rag.md) 为准。

## 5. 阶段 1：工程基础、领域模型与数据基线

### 5.1 目标

建立所有后续业务依赖的可启动工程、领域规则和数据结构，但不实现业务 Controller、模型调用、知识索引或聊天。

### 5.2 必须交付

- 完成配置属性绑定、统一时钟/ID 抽象、固定操作者、`ApiResult<T>`、`PageResult<T>`、全局异常映射和 `traceId` 基础设施。
- 实现工单、托管文档、已解决案例和异步任务的领域实体、值对象、枚举及状态迁移规则。
- 创建一次性初始 Flyway 迁移，完整建立七张一期表、索引、唯一约束和中文 `COMMENT`。
- 建立 DO、领域对象转换和 Repository 端口/基础实现；不同层模型不得复用。
- 创建 MySQL、Redis、Elasticsearch + ICU 的 `deploy/compose.yml` 及必要 Dockerfile，服务只绑定 localhost。
- 配置 liveness、readiness、info 端点和不同 Profile 的安全暴露策略。
- 增加 ArchUnit 测试，锁定模块依赖和 AgentScope 只能出现在 Agent 模块。

### 5.3 重点测试

- 所有领域状态合法迁移、非法迁移、必填约束和版本递增。
- Flyway 从空库迁移成功，表、字段、索引、约束及中文注释完整。
- 应用在缺少 DashScope Key 的开发环境可启动，生产环境缺少 Key 时失败。
- liveness/readiness 在依赖健康和不可用场景下符合设计。

### 5.4 阶段验收

执行 `mvn test` 和 `mvn verify -Pintegration`。Compose 三项基础设施健康，应用可从 IDEA 或 Maven 启动。不得出现业务假接口、空 Repository 或后续阶段占位实现。

### 5.5 本阶段依据

[系统架构与模块边界](02-architecture.md)、[领域模型与字段字典](03-domain-and-data-model.md)、[异步、一致性、安全、运维与测试](06-engineering-and-operations.md)。

## 6. 阶段 2：工单、幂等与异步任务核心

### 6.1 目标

在不依赖 AI 和知识检索的前提下，实现工单基础流程、外部幂等和通用持久化任务调度能力。

### 6.2 必须交付

- 实现手工创建工单草稿、详情、分页、修改、提交和关闭 API；`resolve` 延后到阶段 5，与案例闭环同时交付。
- 实现 `TicketQueryUseCase`，为后续 `get_ticket` 工具提供只读、脱敏的数据边界。
- 实现外部幂等记录：同 Key 同请求返回首次结果，同 Key 不同请求返回冲突，租约超时可恢复。
- 实现工单编号 `T` + 12 位数字、乐观锁、状态校验和固定操作者审计。
- 实现 `async_task` 通用创建、抢占、续租、退避、完成、死亡和取消机制。
- Worker 按每 2 秒、单批 10 条、并发 2、租约 5 分钟运行；最多尝试 3 次，退避 30 秒、2 分钟、10 分钟。
- 实现异步任务详情、分页和 `DEAD` 任务人工重试 API；人工重试创建新任务，不复活原任务。
- 提供对应 OpenAPI 中文字段说明和 IDEA HTTP Client 文件。

### 6.3 重点测试

- 工单状态、编号、分页、版本冲突和重复请求。
- 幂等并发占用、请求哈希冲突、首次结果复用和租约恢复。
- MySQL `FOR UPDATE SKIP LOCKED` 多 Worker 抢占、崩溃恢复、退避及人工重试。
- Controller 不得直接依赖 Mapper，接口不得泄露内部 ID、锁和堆栈。

### 6.4 阶段验收

执行 `mvn test`、`mvn verify -Pintegration`，并用 `http/` 示例完成工单及任务主路径验证。不得实现 Chat、知识发布、工单解决或案例生成。

### 6.5 本阶段依据

[产品范围与业务闭环](01-product-scope.md)、[领域模型与字段字典](03-domain-and-data-model.md)、[API、统一响应与 SSE 契约](04-api-and-sse.md)、[异步、一致性、安全、运维与测试](06-engineering-and-operations.md)。

## 7. 阶段 3：托管知识与 Elasticsearch 索引

### 7.1 目标

完成托管文档从草稿、发布到归档的全生命周期，使已发布内容可靠进入版本化 Elasticsearch 索引，但暂不提供用户问答。

### 7.2 必须交付

- 实现直接文本、单个 Markdown/TXT 文件的创建、查询、分页、修改、发布、归档和草稿软删除 API。
- 实现扩展名、媒体类型、UTF-8、1 MiB 上限、控制字符和敏感凭据检查；不下载 Markdown 远程资源。
- 实现 Markdown 标题感知、TXT 段落感知的确定性分块，以及八类 `exactTerms` 提取和版本记录。
- 实现 `EmbeddingModelPort.embedDocuments` 及 DashScope 适配，严格校验批量数量、顺序和 1024 维度。
- 创建版本化 Mapping、ICU Analyzer、向量字段和别名；实现确定性 `_id`。
- 实现 `KNOWLEDGE_INDEX`、`KNOWLEDGE_DELETE` Handler、Bulk 逐项校验、完整性校验、失败清理和最终状态回写。
- 归档先更新 MySQL 并创建删除任务；索引删除失败时，后续检索仍必须能排除失效来源。
- 提供 OpenAPI、HTTP 示例和发布任务查询路径。

### 7.3 重点测试

- 文件安全、内容哈希、重复内容、编辑/发布/归档状态约束。
- 中英文标题、代码块、列表、超长段落、碎片合并和命令严格提取。
- Embedding 响应错位、缺失、维度错误和临时故障重试。
- Bulk 部分失败、版本过期、重复任务、最终失败、归档删除和别名切换。

### 7.4 阶段验收

执行 `mvn test`、`mvn verify -Pintegration`；获得真实密钥授权时再执行 `mvn verify -Ponline-test`。至少一份 Markdown、一份 TXT 和一份直接文本可发布并在索引中验证，归档后不可作为有效来源。不得提前实现检索融合、Rerank、Agent 或 SSE。

### 7.5 本阶段依据

[领域模型与字段字典](03-domain-and-data-model.md)、[API、统一响应与 SSE 契约](04-api-and-sse.md)、[Agent、模型与 RAG 设计](05-agent-and-rag.md)、[异步、一致性、安全、运维与测试](06-engineering-and-operations.md)。

## 8. 阶段 4：混合检索、Agent 与 SSE 对话

### 8.1 目标

实现一期核心问答链路：意图识别、受控混合检索、带引用回答、无知识建议、工单只读查询和安全 SSE 输出。

### 8.2 必须交付

- 实现四类意图的规则优先识别、结构化模型识别、独立查询改写和低置信度降级。
- 实现 `embedQuery`、BM25/向量并行召回、单分支降级、RRF、Rerank、Top 5 选择和三态结果。
- 实现来源有效性回查、同来源最多 2 个分块、冲突披露、Rerank 降级门槛和初始 0.35 可靠阈值配置。
- 实现 AgentScope `ReActAgent`，仅注册 `get_ticket(ticketNo)`；应用层强制控制路由和检索，Agent 不获得写权限。
- 实现 UTF-8 Markdown Prompt、变量允许名单、Schema 版本和 Prompt 短 SHA-256。
- 实现引用分配与确定性答案校验；精确命令、路径、配置、端口和版本必须可由证据支持。校验失败只允许重新生成一次。
- 实现 Redis 会话版本、运行租约、20 轮限制、7 天 TTL、消息幂等和失败不落半轮。
- 实现 `agent_run`、`retrieval_trace` 摘要记录，不保存完整 Prompt、知识正文、模型输出或隐藏推理。
- 实现 Chat SSE 全部事件、安全片段缓冲、慢客户端终止和长度限制。
- 实现 `ticket.suggested` 及从有效建议生成工单草稿；建议有效期 24 小时且只能消费一次。

### 8.3 重点测试

- 四类意图、规则优先、模型失败和工单号混合技术问题路由。
- BM25、向量、混合、Rerank、单分支降级、双分支失败和可靠门槛。
- 引用未知、精确值无证据、敏感信息、二次生成仍失败等安全场景。
- 会话版本冲突、并发运行、消息重复、租约恢复、TTL 和断流。
- SSE 事件字段、顺序、终止语义，以及 `RETRIEVAL_FAILED` 不产生建单建议。

### 8.4 阶段验收

执行 `mvn test`、`mvn verify -Pintegration`；模型适配完成后，在授权环境执行 `mvn verify -Ponline-test`。使用 HTTP 示例验证问候、引用回答、无知识建议、检索失败、工单查询和越界请求。不得实现案例生成、案例 API 或自动参数调优。

### 8.5 本阶段依据

[API、统一响应与 SSE 契约](04-api-and-sse.md)、[Agent、模型与 RAG 设计](05-agent-and-rag.md)、[异步、一致性、安全、运维与测试](06-engineering-and-operations.md)。

## 9. 阶段 5：案例闭环、评估与一期验收

### 9.1 目标

打通工单解决、AI 案例草稿、人工审核发布、再次检索的闭环，并完成一期整体测试、评估和交付文档。

### 9.2 必须交付

- 实现工单 `OPEN -> RESOLVED` API，要求人工填写 `rootCause` 和 `solution`，并在同一事务创建 `CASE_GENERATION` 任务。
- 实现案例结构化生成，只能整理来源工单既有事实；模型不得生成 ID、状态、版本、哈希、操作者或时间。
- 实现案例查询、分页、修改、发布、拒绝和归档 API，以及 `DRAFT`、`PUBLISHING`、`PUBLISHED`、`PUBLISH_FAILED`、`REJECTED`、`ARCHIVED` 全部规则。
- 案例发布复用知识分块、Embedding、索引和删除机制；托管文档优先于案例，冲突时必须披露。
- 建立约 50 条固定 JSONL 评估集，覆盖已知知识、精确术语、同义改写、无知识和知识冲突。
- 实现 `BM25_ONLY`、`VECTOR_ONLY`、`HYBRID`、`HYBRID_RERANK` 对比，报告 Recall@5、MRR@10、nDCG@5、无命中准确率和精确词召回率；不得自动修改线上参数。
- 完善所有 OpenAPI 中文字段说明、IDEA HTTP Client、中文 README、健康检查、清理策略和运行说明。
- 对照一期最终验收清单完成端到端回归，不包含任何已明确排除的功能。

### 9.3 重点测试

- 解决工单与任务插入的事务原子性、重复解决、版本冲突和任务幂等。
- 案例 Schema 校验、事实边界、任务重试、人工编辑、发布失败、拒绝和归档。
- 文档与案例来源冲突、归档后失效、完整业务闭环和敏感信息防护。
- 四种检索模式报告可重复生成，评估结果不改变运行配置。

### 9.4 阶段验收

执行 `mvn test`、`mvn verify -Pintegration`，并在授权环境执行 `mvn verify -Ponline-test`。逐项核对 [一期验收标准与当前骨架边界](07-delivery-scope.md) 的最终完成标准；未验证项目必须明确记录复现命令、原因和风险，不能用环境限制掩盖未完成实现。

### 9.5 本阶段依据

[产品范围与业务闭环](01-product-scope.md)、[领域模型与字段字典](03-domain-and-data-model.md)、[API、统一响应与 SSE 契约](04-api-and-sse.md)、[Agent、模型与 RAG 设计](05-agent-and-rag.md)、[异步、一致性、安全、运维与测试](06-engineering-and-operations.md)、[一期验收标准与当前骨架边界](07-delivery-scope.md)。

## 10. 每阶段统一完成清单

每个阶段结束时，逐项确认：

- [ ] 只实现了当前阶段范围，没有提前进入下一阶段。
- [ ] 所有新增类和方法都有准确 Javadoc。
- [ ] 所有新增字段、配置、状态、错误码和接口都有中文含义、约束和示例。
- [ ] 已执行阶段要求的单元、集成及必要在线测试。
- [ ] 未执行或失败的验证已记录原因、影响和复现方式。
- [ ] 已检查完整 Git 差异、未跟踪文件、敏感信息和无关改动。
- [ ] 已完成代码 Review，问题已修复并重新检查。
- [ ] 已更新 `docs/work-logs/` 对应阶段记录。
- [ ] 已向用户汇报并停止，等待是否提交或进入下一阶段的明确指令。

## 11. AI 执行指令模板

后续可使用以下格式启动单个阶段：

```text
请执行 docs/implementation-plan/00-phased-implementation-plan.md 的第 N 阶段。
严格遵守 docs/standards/ 下的规范，只实现本阶段内容。
完成后执行规定测试和完整 Review，更新阶段工作记录；不要提前执行下一阶段，也不要自动提交或推送。
```

若只要求规划或评审，不得据此直接修改业务代码。
