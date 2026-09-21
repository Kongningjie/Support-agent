# 阶段工作记录：阶段 15 Prompt 信任边界与注入防护

## 基本信息

| 字段 | 内容 | 字段含义 |
|---|---|---|
| 日期 | 2026-09-21 | 本阶段开始日期 |
| 阶段 | stage-15-prompt-security-boundary | 四期阶段 15 Prompt 信任边界与注入防护 |
| 执行者 | Codex | 实际实施和记录负责人 |
| 关联计划 | [阶段 15](../implementation-plan/11-phase-4-llm-security-plan.md#5-阶段-15prompt-信任边界与注入防护) | 本阶段唯一实施依据 |
| 状态 | 已完成 | 已通过离线、集成、敏感信息扫描和完整 Review 门禁 |

## 阶段目标与验收标准

- 目标：为当前用户消息、会话历史、滚动摘要、长期记忆、检索证据和工单字段建立统一的不可信数据边界，阻断高置信度直接注入并排除高风险上下文。
- 验收标准：直接与间接注入、合法安全讨论、上下文排除、数据边界转义和阻断请求无副作用均有自动化验证，并通过 `mvn test`、`mvn verify -Pintegration`、敏感信息扫描和完整 Review。
- 不在范围：阶段 16 的统一输出安全网关、随机泄漏标记、全部模型分支输出校验，以及阶段 17 的 80 条固定评测集和安全指标闭环。

## 实施记录

- 在应用层新增独立 Prompt 安全策略，使用确定性的中英文信号组合识别规则覆盖、Prompt 索取、角色伪造、工具越权和编码执行意图，并保留合法安全讨论的 `GUARD` 语义。
- 当前消息在会话创建、检索和模型调用前完成同步检查；`BLOCK` 返回 HTTP 422 和 `CHAT_PROMPT_INJECTION_BLOCKED`，没有会话、审计或模型副作用。
- 检索证据的标题、标题路径和正文逐块检查；历史、摘要、长期记忆和工单可变字段在组装模型上下文时检查。高风险项只从本次调用排除，不删除原始数据。
- 为问候、知识回答、意图、摘要、记忆候选、工单 Agent、工单草稿和案例生成统一加入转义后的不可信数据区与事实边界声明。
- 增加三个生产不可关闭的阶段 15 配置项、422 错误映射、HTTP Client 示例和运行文档。

## 文件与契约变化

- 新增 `security` 应用包，核心契约为 `PromptSecurityPolicy`、`PromptSecurityAssessment`、`PromptSecurityAction`、`PromptSecuritySignal`、`PromptSecuritySource` 和 `LlmSecuritySettings`。
- 新增 Agent 内部 `PromptDataBoundary`，以 `<untrusted_data source="...">` 包装并转义所有动态输入。
- 新增错误码 `CHAT_PROMPT_INJECTION_BLOCKED`，公开响应为 HTTP 422；`GUARD` 与上下文排除均不改变客户端成功契约。
- 新增配置 `SUPPORT_AGENT_LLM_SECURITY_ENABLED`、`SUPPORT_AGENT_LLM_BLOCK_HIGH_CONFIDENCE_INPUT` 和 `SUPPORT_AGENT_LLM_EXCLUDE_HIGH_RISK_CONTEXT`，默认均为 `true`。
- 新增 `http/28-prompt-security.http`；未新增数据库表、公共管理接口、依赖或中间件。

## 设计偏差与确认

- 无设计偏差。阶段 16 的输出安全网关、随机泄漏标记与全部分支输出校验均未提前实现；阶段 17 的固定 80 条对抗评测和安全指标亦未实现。

## Review 记录

- Review 范围：阶段 15 新增安全策略、聊天与上下文编排、全部 Prompt 适配、错误映射、生产配置、测试、HTTP 示例和文档；同时检查工作区内尚未提交的阶段 14 状态同步与分页修正，未覆盖或回退既有变更。
- 发现并修复：最初只检查证据正文，补充标题和标题路径；安全策略依赖可能为空，改为构造期拒绝；角色伪造与编码指令的阻断组合改为要求明确执行意图；工单 Agent 各数据区增加换行分隔。
- Review 结论：未发现遗留的阻断级或重要问题；模块依赖、错误语义、数据不删除原则和阶段边界符合冻结方案。

## 测试与验证

- 聚焦测试：35 项通过，覆盖策略组合、合法安全讨论、直接阻断无副作用、证据标题与正文排除、工单字段隐藏、历史/摘要/记忆排除、边界转义、HTTP 422 和生产配置约束。
- `mvn test`：通过，共 222 项，失败 0、错误 0、跳过 0。
- `mvn verify -Pintegration`：通过；Docker Desktop 29.7.2 可用，共 47 项集成测试，覆盖 MySQL 8.4.11、Redis、Elasticsearch 9.5.2 和应用启动。
- `git diff --check`、敏感信息扫描、调试标记扫描和 Markdown 本地链接检查均通过。
- 首次聚焦测试命令因 PowerShell 未给 `-Dsurefire.failIfNoSpecifiedTests=false` 加引号而被 Maven 解析为生命周期阶段；修正命令格式后测试通过，该问题不涉及代码。
- Review 补充回归首次运行时，新测试夹具使用了领域对象禁止的覆盖版本 `0`，导致构造期错误；将夹具修正为合法版本后，相关 11 项测试和完整 222 项回归均通过，产品代码未因测试失败而降级。
- 未运行 `mvn verify -Ponline-test`：阶段 15 门禁不要求真实模型调用，真实模型对抗效果按冻结计划留到阶段 17 且需单独授权。

## 风险、限制与未完成项

- 当前策略是确定性规则，阶段 15 只声明直接与间接注入的输入边界，不代表真实模型攻击防御率已经量化。
- 阶段 16 前，问候、工单回答和模型业务草稿尚未统一接入输出安全网关；现有 RAG 校验保持不变。
- 阶段 17 前，尚无固定中文安全评测集、攻击处置率、误阻断率和安全低基数指标。
