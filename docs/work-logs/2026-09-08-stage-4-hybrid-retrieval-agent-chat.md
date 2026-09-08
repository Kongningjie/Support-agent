# 阶段工作记录：混合检索、Agent 与 SSE 对话

## 基本信息

| 字段 | 内容 | 字段含义 |
|---|---|---|
| 日期 | 2026-09-08 | 阶段开始日期 |
| 阶段 | stage-4-hybrid-retrieval-agent-chat | 总实施计划第 4 阶段 |
| 执行者 | Codex | 实际实施、验证与 Review 负责人 |
| 关联计划 | [阶段 4](../implementation-plan/00-phased-implementation-plan.md#8-阶段-4混合检索agent-与-sse-对话) | 本阶段执行依据 |
| 状态 | 已完成 | 交付物、测试、Review 和阶段记录均已完成 |

## 阶段目标与验收标准

- 实现规则优先意图识别、受控混合检索、可靠知识门槛和确定性答案校验。
- 实现仅带只读 `get_ticket` 工具的 AgentScope `ReActAgent` 与安全 SSE 对话。
- 实现 Redis 会话、运行租约、消息幂等、工单建议及显式消费建单。
- 实现 `agent_run`、`retrieval_trace` 安全摘要记录，并通过单元、集成、HTTP 与完整 Review 门禁。
- 不实现阶段 5 的案例生成、案例管理、固定评估集或自动调参。

## 已确认实现边界

- 模型内部可流式接收，但客户端只接收完整校验后按自然段或 100～300 字符切分的安全片段；空闲 15 秒发送 SSE 注释心跳。
- Redis 保留 20 个完整轮次；模型最多使用最近 6 轮且历史总长不超过 12000 字符。
- 向量最低余弦相似度为 0.20，Top 50、`num_candidates=200`；BM25 Top 50；RRF `k=60` 后 Top 30；Rerank 阈值 0.35 后 Top 5。
- 工单建议保存 24 小时并使用 Redis 状态租约与 MySQL `(conversation_id, source_turn_id)` 唯一约束防止重复消费。
- 应用层会话是权威状态，AgentScope 状态只作为受控附属数据在成功轮次中原子保存。
- 本地模型凭据只使用 IDEA Run Configuration 注入，不引入 `.env` 加载。

## 已完成工作

- 已完整读取阶段计划、专题设计、强制规范和上一阶段工作记录。
- 已通过 `grill-me` 逐项确认路由、检索、引用、SSE、会话、建议消费和模型配置边界。
- 已实现规则优先、模型结构化识别、独立问题改写、0.70 置信度门槛及保守降级。
- 已实现 BM25 与向量并行召回、RRF、Rerank、来源有效性回查、同来源限额、三态检索和冲突披露。
- 已实现 Chat、Embedding、Rerank 三个独立 DashScope 端口，所有适配器显式使用 `DASHSCOPE_HTTP_BASE_URL`。
- 已实现只注册 `get_ticket(ticketNo)` 的 AgentScope `ReActAgent`，应用层预先绑定唯一工单且工具至多调用一次。
- 已实现完整答案缓冲、引用和精确值校验、一次完整重生成、安全分片、SSE 心跳及慢客户端终止。
- 已实现 Redis 会话版本、运行租约、消息幂等重放、20 轮/7 天保留，以及 24 小时一次性工单建议。
- 已实现建议显式消费建单、MySQL 来源轮次唯一约束，以及 `agent_run`、`retrieval_trace` 安全摘要。
- 已补充真实 HTTP 固定分支集成验证、IDEA HTTP Client 示例及三类 DashScope 在线测试入口。

## 文件与契约变化

- `support-agent-application`：新增聊天、检索、模型端口、审计/会话端口和建议建单用例。
- `support-agent-agent`：新增 AgentScope Chat/意图适配器、DashScope Rerank 适配器、Prompt 模板及安全渲染器。
- `support-agent-infrastructure`：扩展 Elasticsearch 查询，新增 Redis 会话、MySQL 来源回查、审计 Mapper 和 Flyway V3。
- `support-agent-interfaces`：新增 `POST /api/v1/chat/stream` 和 `POST /api/v1/tickets/drafts/from-conversation`。
- `support-agent-bootstrap`：新增阶段 4 装配、模型与检索配置；无密钥的开发环境仍可启动并按能力降级。
- `pom.xml`：普通集成测试明确排除 `*OnlineIT`，防止仅因环境存在密钥而触发付费调用。

## 设计偏差与确认

- 用户最终选择 IDEA Run Configuration 注入 `DASHSCOPE_API_KEY` 与 `DASHSCOPE_HTTP_BASE_URL`，取消此前讨论的 `.env` 导入方案。

## Review 记录

- Review 范围：全部已跟踪差异、未跟踪文件、模块依赖、公共接口、SSE 顺序、并发/幂等、配置、迁移、测试和文档。
- 首轮修复：提交前预留终结事件序号；慢客户端等待真实发送确认；提交后断流不回滚成功轮次；缺失密钥改为能力降级；补齐受控工单工具和审计。
- 复审修复：同步执行会话版本/幂等/租约检查；UUID 明确编码为 `BINARY(16)`；SSE 改为每任务虚拟线程，消除单连接占满固定线程池；意图输出读取真实文本块；工具错误调用也计入一次限制。
- 安全复审修复：Prompt 改为单次匹配替换，禁止用户输入触发二次占位符展开；普通集成 Profile 排除在线测试；扫描未发现真实密钥、完整 Prompt/模型输出日志、临时代码或遗留 TODO。
- 复审结论：上述问题均已闭环；未发现阶段 5 的案例生成、案例 API、评估集或自动调参实现。`git diff --check` 仅提示 Windows 工作区 LF/CRLF 转换，无内容错误。

## 测试与验证

- `mvn test`：通过，共 84 个单元/架构/接口测试，0 失败、0 错误、0 跳过。
- `mvn verify -Pintegration`：通过；MySQL 8.4.11、Redis 7.4.7、Elasticsearch 9.5.2、Flyway V1～V3、完整应用启动和 OpenAPI 路径均验证成功。
- 最终受影响集成复测：`ApplicationStartupIT` 共 5 个测试通过，新增真实 Redis + MySQL 下的越界分支 SSE 顺序、结果状态和会话版本验证。
- `mvn verify -Ponline-test`：未执行。原因是用户未明确授权真实 DashScope 计费调用；Chat、Embedding、Rerank 三个 `*OnlineIT` 已准备，并会读取 IDEA/进程环境中的密钥和 Base URL。
- `http/20-chat.http`：已提供问候、技术支持、工单查询、越界、后续轮次和建议建单示例；依赖真实模型的人工 HTTP 验证留待在线授权环境执行。

## 风险、限制与未完成项

- 真实 DashScope 自定义 MaaS 工作空间的 Chat、Embedding、Rerank 响应尚未在线验证；若地址、模型授权或供应商协议不匹配，运行时将进入明确失败或检索降级，而不会输出未经校验的回答。
- SpringDoc 生成文档时仍输出一个 Jackson Schema 兼容性警告，但本阶段新增路径存在且 OpenAPI 路径断言通过；未在本阶段扩展或替换文档技术栈。

## 下一阶段建议

- 停止在阶段 4，不自动提交、不推送、不提前执行阶段 5；等待用户确认后再决定提交或启动下一阶段。
