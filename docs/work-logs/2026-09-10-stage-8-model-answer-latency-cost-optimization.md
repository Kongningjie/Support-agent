# 阶段工作记录：模型回答、延迟与成本优化

## 基本信息

| 字段 | 内容 | 字段含义 |
|---|---|---|
| 执行日期 | 2026-09-10 | 本阶段实际实施和验收日期 |
| 阶段 | stage-8-model-answer-latency-cost-optimization | 二期优化计划第 8 阶段 |
| 关联计划 | [阶段 8](../implementation-plan/08-phase-2-optimization-plan.md#7-阶段-8模型回答延迟与成本优化) | 本阶段唯一实施依据 |
| 状态 | 已完成 | 实现、真实评测、全部规定测试和完整 Review 已通过 |

## 冻结决策

- 回答评测集固定为 30 条中文用例：明确知识 10 条、精确值 5 条、无知识 5 条、知识冲突 5 条、案例结构化 5 条；使用人工标注和程序硬规则，不使用 LLM 裁判。
- 同一 Prompt、`temperature=0.0`、关闭思考和同一输出上限下比较三个候选；质量硬门禁优先，再比较首轮稳定性、完整回答 P95、Token 和 Flash 偏好。
- 每个问题默认只执行一次生产链路；完整答案校验失败时最多完整重生成一次。整条链路最终失败后，评测器才额外做一次稳定性复测；两类次数分开记录。
- 每个候选的本阶段累计硬上限为 100,000 Token，80,000 预警；正式 Chat、Intent、工单和案例的输出上限分别为 1,200、256、800 和 800 Token。
- Chat、Intent、工单、案例生成超时分别为 120 秒、3 秒、30 秒和 60 秒；本阶段只配置化，网络重试细化留给阶段 9。
- 正式 Chat 模型冻结为 `qwen3.8-flash`，Prompt 保留 `ORIGINAL`。
- 单会话滚动摘要拆分为后续独立阶段；跨会话长期记忆只进入总体规划，第 8 阶段均不实现。

## 实施内容

| 范围 | 变更 | 目的 |
|---|---|---|
| `support-agent-application` | 新增回答评测用例、标注结构、硬门禁评估器和模型选择策略 | 让选型规则与 AgentScope/供应商解耦，并可单元测试 |
| `support-agent-infrastructure` | 新增带版本和 SHA-256 校验的 30 条固定回答评测集加载器 | 防止为了分数静默修改数据或类别分布 |
| `support-agent-agent` | Chat/Intent 使用 AgentScope OpenAI 兼容模型访问工作空间地址；新增超时、Token 上限、Prompt 版本和 `temperature=0.0` | 兼容当前 DashScope 工作空间 API，保持 Chat、Embedding、Rerank 适配接口独立 |
| `support-agent-bootstrap` | 新增 `stage-8-optimization` Profile、真实三模型评测和脱敏 JSON 报告；默认 Chat 模型改为 `qwen3.8-flash` | 只在显式 Profile 下消耗真实 Token，动态报告保存到 `target/` |
| 配置与 Prompt | README、`.env.example`、默认配置和在线测试回退值同步正式模型；原 Prompt 加强逐句引用、精确值和冲突约束；新增仅供 A/B 的压缩版 | 正式运行不依赖 `.env` 自动加载，不提交真实密钥 |

工作空间的原生 `/api/v1` 端点不接受 AgentScope 原生 DashScope 适配器拼接的公网路径；Chat 和 Intent 因此转换为同主机 `/compatible-mode/v1`。Embedding 和 Rerank 仍使用原生地址，没有更改统一 DashScope 供应商边界。

评测集在正式模型比较前由 v1.0 修正至 v1.2：仅处理可证明的标注假阳性，包括否定语句命中禁用词、配置键子串重叠和问题未要求的精确值。v1.2 冻结后未再根据候选模型结果调整用例。

## 实验与验收结果

最终正式报告：`runId=f644c255-3ef9-4fea-adeb-07e2bff8508e`，数据集 `answer-evaluation-v1.2`，SHA-256 为 `72145c50f423b1df3f108fa64883daa71042f9ecd0df15f9a86c2a843104f201`。报告在 `support-agent-bootstrap/target/stage-8-optimization/` 中生成，不纳入 Git。

| 候选 | 最终质量 | 首轮全通过 | 生产重生成 | 稳定性复测 | 完整 P95 | Token | 结论 |
|---|---:|---:|---:|---:|---:|---:|---|
| `qwen3.7-flash-2026-07-15` | 未通过 | 否 | 2 | 3 | 2,224 ms | 10,541 | 仍有 1 条必需事实缺失，淘汰 |
| `qwen3.8-flash` | 通过 | 是 | 0 | 0 | 3,186 ms | 8,463 | 胜出并写入默认配置 |
| `qwen3.8-max-0902` | 未通过 | 否 | 2 | 1 | 4,049 ms | 10,101 | 仍有 1 条精确值不受证据支持，淘汰 |

`qwen3.8-flash` 压缩 Prompt 仅减少 3.21% 输入 Token，未达 10% 门槛；完整 P95 从 3,186 ms 上升到 31,531 ms，且 3 条冲突用例最终失败，因此拒绝采用。

开发期共生成 8 份过程报告；报告可计量 Token 合计分别为 70,444、81,222、70,314（按上表三个候选顺序）。`qwen3.8-flash` 超过 80,000 预警线但仍低于 100,000 硬上限；其他候选未触发预警。过程中曾出现单轮结论波动，促使我们修正“生产重生成”与“稳定性复测”的混合统计，并显式固定温度为 0.0；未删除或放宽质量硬门禁。

### 报告字段含义

| 字段 | 含义 |
|---|---|
| `qualityPassed` | 稳定性复测结束后，全部 30 条是否均通过硬门禁 |
| `firstRoundAllPassed` | 所有用例的第一条生产链路是否均无失败且无重生成 |
| `productionRegenerationCount` | 生产答案校验失败后，服务端内部完整重生成的用例数 |
| `stabilityRerunCount` | 一条生产链路最终失败或调用异常后，评测器额外复测的用例数 |
| `firstTokenLatency` | 仅对流式文本生成统计首个非空 Token 到达时间 |
| `completeLatency` | 从用例开始到完整答案、校验及必要重生成结束的时间 |
| `inputTokens` / `outputTokens` / `totalTokens` | 供应商返回的输入、输出及两者合计 Token，包含重生成和复测 |
| `failures` | 最终仍未通过的程序化硬门禁代码，不保存模型正文 |
| `initialValidationFailures` | 同一生产链路中第一次完整答案的校验失败代码 |
| `firstEvaluationFailures` | 进入稳定性复测前，首条完整生产链路的最终失败代码 |

## 测试结果

- `mvn test`：已通过 128 项单元测试，0 失败、0 错误、0 跳过。
- `mvn verify -Pstage-8-optimization`：已通过，最终真实模型评测 1 项通过。
- `mvn verify -Pintegration`：通过 20 项集成测试，MySQL 8.4.11、Redis、Elasticsearch、Flyway 和应用启动均通过。
- `mvn verify -Ponline-test`：通过 6 项真实在线测试，覆盖 Chat、20 并发、Embedding、Rerank 和阶段 7 真实检索回归。
- 既有 Mockito 动态 Agent 弃用警告和 Testcontainers 关闭后 Redis 重连警告仍存在；本轮无测试失败，前者留给依赖治理，后者是测试容器销毁后的非阻断日志。

## Review 记录

- Review 范围：全部已跟踪差异和新文件，包含六模块依赖方向、配置绑定、AgentScope 地址适配、生产重生成/稳定性复测口径、Token 预算、评测数据哈希、Prompt、Javadoc 和 Maven Profile。
- Review 发现并已修正：原生 AgentScope 路径与工作空间地址不兼容、生产重生成与稳定性复测混合计数、普通 Chat 未显式固定温度、压缩 Prompt 失败明细未入报告、默认模型和示例 Base URL 未同步，以及一处布尔契约表达式可读性问题。
- 安全 Review：`.env` 和动态 `target/` 报告均被 Git 忽略；使用当前真实 Key 对全部已跟踪及待跟踪文件做精确扫描，无命中；报告不保存问题、证据、Prompt 或模型正文。
- Review 结论：通过，无未处理阻断问题；`git diff --check` 通过。

## 阶段边界

- 不实现会话摘要服务、Redis 摘要状态或跨会话长期记忆。
- 不提前执行阶段 9，不自动提交或推送。
