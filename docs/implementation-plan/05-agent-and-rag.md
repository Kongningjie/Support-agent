# Agent、模型与 RAG 设计

## 1. Agent 边界

一期只使用 AgentScope `ReActAgent`，`maxIters=3`。应用层强制执行检索，Agent 无权决定跳过知识检索。

Agent 只有一个只读工具：

```text
get_ticket(ticketNo)
```

- `ticketNo`：格式为 `T` + 12 位数字的工单编号。
- 工具通过应用层 `TicketQueryUseCase` 查询，不直接访问 Mapper。
- 工具只返回允许公开的工单字段。
- 不提供创建、修改、解决、关闭、发布、知识搜索、SQL、文件系统或 Shell 工具。

## 2. DashScope 模型

| 能力 | 模型 | 用途 |
|---|---|---|
| Chat | `qwen3.7-plus-2026-05-26` | 非思考模式；流式回答、工具调用、结构化工单和案例生成 |
| Intent | `qwen3.7-flash` | 结构化意图识别和独立检索问题改写 |
| Embedding | `text-embedding-v4` | 生成 1024 维查询和文档向量 |
| Rerank | `qwen3-rerank` | 对融合后的前 30 个候选重新排序 |

不配置备用模型或备用供应商。

模型端口按能力和任务拆分：

- `ChatModelPort`：问候、边界回复、知识回答、工单回答、工单草稿和案例草稿。
- `IntentRecognitionPort`：意图及独立查询识别。
- `EmbeddingModelPort`：`embedQuery` 与 `embedDocuments`。
- `RerankModelPort`：按稳定 `chunkId` 返回重排结果。

Embedding 批量结果的数量、顺序和维度必须与输入一致。Rerank 返回未知、重复或缺失 `chunkId` 时整次降级，不能按数组下标猜测关联关系。

## 3. 意图识别

输出 Schema：

| 字段 | 含义 |
|---|---|
| `intent` | `GREETING`、`SUPPORT_QUERY`、`TICKET_QUERY` 或 `OUT_OF_SCOPE` |
| `confidence` | 置信度，范围 0～1；规则命中为 1 |
| `standaloneQuery` | 结合有限近期上下文得到的独立检索问题，只用于检索 |
| `ticketNo` | 提取出的工单编号，非工单意图或未提供时为空 |
| `reasonCode` | `RULE_TICKET_NO`、`MODEL_CLASSIFIED`、`LOW_CONFIDENCE_FALLBACK` 等稳定原因码 |

规则优先处理明确工单号、纯问候和明显越界请求，其余调用意图模型。低置信度或模型失败默认 `SUPPORT_QUERY`。

路由规则：

- `GREETING`：不检索、不调用工具，生成简短问候。
- `SUPPORT_QUERY`：必须走完整 RAG；只有 `NO_RELIABLE_KNOWLEDGE` 可以建议建单。
- `TICKET_QUERY`：校验编号后允许调用 `get_ticket`；缺少编号时询问用户；不存在时不建议新建。
- `OUT_OF_SCOPE`：不检索、不调用工具，返回固定边界提示。

同一消息同时包含工单号和新技术问题时优先 `SUPPORT_QUERY`。任何写操作请求只告知用户调用对应 API，Agent 不执行。

## 4. 文档解析与分块

- Markdown 按标题层级感知分块。
- TXT 按段落感知分块。
- 目标长度 800 个 Unicode 字符，最大 1200。
- 仅强制切分时产生 120 字符重叠。
- 小于 80 字符的碎片优先合并。
- 尽量保留标题路径、代码块和列表完整性。
- 分块过程确定性执行，不使用 AI 语义分块。
- 每个来源保存 `contentHash` 和 `chunkStrategyVersion`；策略变化需要重建索引。

## 5. 精确技术词 `exactTerms`

一期只保留：

| 类型 | 含义 |
|---|---|
| `ERROR_CODE` | 产品、协议或异常错误码 |
| `VERSION` | 产品、组件、协议或运行时版本 |
| `COMMAND` | 有明确上下文且可执行的命令 |
| `CONFIG_KEY` | 配置项名称 |
| `FILE_PATH` | Windows、Linux 或配置文件路径 |
| `TICKET_NO` | `T` + 12 位数字的工单号 |
| `CLASS_OR_PACKAGE` | Java 类、异常类或完整包名 |
| `URL_OR_ENDPOINT` | 完整 URL 或 API 路径 |

不单独提取 `IP_ADDRESS`、`PORT`、`HASH_OR_ID`；它们仍可通过正文 BM25 命中。URL 中主机和端口作为完整 `URL_OR_ENDPOINT` 保存。

每个精确词字段：

- `type`：上表中的类型。
- `normalizedValue`：用于精确匹配的规范化值。
- `displayValue`：原文展示值。
- `sourceOffsetStart`：在分块正文中的起始字符位置。
- `sourceOffsetEnd`：在分块正文中的结束字符位置。

单个分块去重后最多保留 100 个精确词。提取器记录 `exactTermExtractorVersion`，规则变更需要重建索引。

### `COMMAND` 严格规则

只从以下位置提取：

- Markdown 的 Bash、Shell、PowerShell 围栏代码块。
- Markdown 行内代码。
- TXT 中有明显命令提示符或命令前缀的独立行。
- “执行命令”“运行以下命令”等明确说明的下一行。

Java、JSON、YAML、SQL 等代码块不整体视为命令。多行命令作为一个值；去除 `$`、`>`、`PS>` 等提示符；保留参数、引号、路径和大小写。单条超过 1000 字符时不进入 `exactTerms`，但仍保留正文。

## 6. Elasticsearch 字段与权重

- `title`：ICU 分析，权重 3。
- `headingPath`：ICU 分析，权重 2。
- `content`：ICU 分析，权重 1。
- `exactTerms.normalizedValue`：`keyword` 精确匹配，权重 5。
- `exactTerms.displayValue`：展示和答案校验，不参与全文分词。
- `embedding`：1024 维向量。

两种知识来源写入同一版本化索引。Mapping 或模型变化时创建新物理索引，重建成功后原子切换 `support_knowledge_current` 别名。

## 7. 混合检索流水线

```text
BM25 Top 50 --------\
                     -> RRF(k=60) Top 30 -> Rerank -> Top 5 -> 证据门槛
Vector Top 50 ------/
```

- BM25 和向量分支并行执行。
- 一个分支失败时使用另一个分支降级。
- 两个分支都失败时为 `RETRIEVAL_FAILED`，禁止生成答案或建议建单。
- 融合在应用层执行 RRF，`k=60`。
- Rerank 输入最多 30 个候选，最终最多 5 个证据。
- 参数全部配置化，不提供在线自动调参。

## 8. 可靠知识门槛

基础过滤：仅接受 MySQL 当前仍为 `PUBLISHED` 且版本有效的来源；去重；同一 `sourceId` 最多 2 个分块；向量分支应用最低相似度配置。

Rerank 正常时，以 `rerankScore` 判定。配置项 `support-agent.retrieval.rerank-grounded-threshold` 初始为 0.35，但该值只是启动值，必须通过固定评估集校准，不视为已验证最佳值。

Rerank 降级时，候选至少满足以下一项才可成为可靠证据：

- 同一 `sourceId` 同时出现在 BM25 和向量结果。
- 查询精确词与知识 `exactTerms` 完全匹配。
- BM25 命中标题或标题路径，并且正文包含主要查询关键词。

检索最终状态：

- `GROUNDED`：至少一个候选通过门槛，必须带有效引用回答。
- `NO_RELIABLE_KNOWLEDGE`：检索正常但没有候选通过门槛，返回安全答复并建议建单。
- `RETRIEVAL_FAILED`：BM25 和向量均失败，不声称没有知识，也不建议建单。

## 9. 来源选择与冲突

- 检索打分不预设来源偏置。
- 组织答案时以 `MANAGED_DOCUMENT` 为主要依据，`RESOLVED_CASE` 为补充。
- 只有案例命中时，明确说明内容来自已审核的历史案例。
- 文档与案例冲突时，以托管文档为主，披露冲突并建议人工确认。
- 同一来源最多选择 2 个分块，防止单一长文档占满上下文。

## 10. 引用协议

服务端为最终证据分配临时标识 `[S1]`、`[S2]`。模型只能引用本次提供的标识。

引用 DTO：

| 字段 | 含义 |
|---|---|
| `citationId` | 临时证据标识，例如 `S1` |
| `documentId` | 来源文档或案例 ID，对外按字符串返回 |
| `documentTitle` | 来源标题 |
| `headingPath` | 分块所属标题路径 |
| `sourceType` | `MANAGED_DOCUMENT` 或 `RESOLVED_CASE` |
| `sourceCaseId` | 来源为案例时的案例 ID，否则为空 |

`GROUNDED` 回答至少包含一个有效引用。未知引用 ID 必须拒绝；被证据门槛过滤的分块不能引用。

## 11. 最终回答校验

一期使用确定性校验，不再调用额外裁判模型：

- 引用 ID 必须存在且放在相应句子或步骤后。
- 命令、路径、配置键、端口、版本等精确内容必须在引用证据中存在。
- 扫描敏感信息，禁止输出凭据。
- 禁止声称使用未提供的来源。
- 工单回答不得输出内部主键、锁字段和任务错误详情。
- 禁止伪造 SSE、接口响应或系统提示词。

第一次校验失败时，把规则编号和原证据交给同一 Chat 模型重新生成一次。再次失败则发送 `CHAT_ANSWER_VALIDATION_FAILED`，不输出未通过答案，也不改写为 `NO_RELIABLE_KNOWLEDGE`。

## 12. 结构化输出

使用 DashScope/AgentScope 原生结构化输出、严格 JSON Schema 和服务端 Bean Validation；不允许正则或自由文本解析降级，拒绝未知字段。

工单草稿模型字段：

- `title`：必填，1～120 字符。
- `problemDescription`：必填，1～4000 字符。
- `attemptedActions`：可空，最大 4000 字符，只能来自会话已知信息。

案例模型字段：

- `title`：必填，1～160 字符。
- `problem`：必填，1～4000 字符。
- `cause`：必填，1～4000 字符，必须来自来源工单。
- `solution`：必填，1～8000 字符，必须来自来源工单。

模型不得生成 ID、编号、来源 ID、会话 ID、状态、版本、哈希、操作者或时间字段。工单草稿同步生成校验失败可立即重试 1 次；案例生成由 `async_task` 重试。

## 13. Prompt 管理

Prompt 使用 UTF-8 Markdown 并随 Git 管理，规划位置：

```text
support-agent-agent/src/main/resources/prompts/
├─ support-agent-system.md
├─ grounded-answer.md
├─ no-knowledge-answer.md
├─ ticket-draft-generation.md
└─ resolved-case-generation.md
```

模板变量采用允许名单，缺失变量立即失败。用户输入和检索证据都标记为不可信内容，不能覆盖系统指令或强迫调用工具。Prompt 内容使用短 SHA-256 作为 `promptVersion` 写入运行轨迹。一期不提供数据库 Prompt 管理或在线编辑器。

## 14. 超时、重试和熔断

| 调用 | 策略 |
|---|---|
| 意图识别 | 总超时 3 秒；不重试；失败降级为 `SUPPORT_QUERY` |
| Embedding | 单次 10 秒；仅网络错误、限流和服务端错误重试 2 次 |
| Rerank | 单次 10 秒；不重试；失败使用 RRF 保守门槛 |
| Chat | 首响应最多 15 秒，整次流最多 120 秒；失败不自动重试，防止重复输出 |
| 工单结构化生成 | 单次 30 秒；Schema 校验或临时服务错误重试 1 次 |
| 案例结构化生成 | 单次 60 秒；由持久化任务按统一退避策略重试 |

使用 Resilience4j 实现超时、并发隔离和熔断。熔断只阻止继续调用，不能把模型故障伪装成“没有知识”。记录用途、模型名、耗时、状态和错误码，不记录完整 Prompt 与模型原文。
