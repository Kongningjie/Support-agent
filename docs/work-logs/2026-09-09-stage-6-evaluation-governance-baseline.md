# 阶段工作记录：评测治理与基线测量

## 基本信息

| 字段 | 内容 | 字段含义 |
|---|---|---|
| 日期 | 2026-09-09 | 本阶段完成日期 |
| 阶段 | stage-6-evaluation-governance-baseline | 二期优化计划第 6 阶段 |
| 执行者 | Codex | 实际实施、验证与 Review 负责人 |
| 关联计划 | [阶段 6](../implementation-plan/08-phase-2-optimization-plan.md#5-阶段-6评测治理与基线测量) | 本阶段执行依据 |
| 状态 | 已完成 | 本地质量、容量、基础设施门禁及真实 DashScope 在线抽样全部通过 |

## 阶段目标与验收标准

- 冻结 50 条回归问题与 15 条语料，新增 150 条优化开发问题，并使用稳定 `sourceKey` 和 SHA-256 防止数据漂移。
- 用相同数据、模型和参数重复执行两次四模式评测，生成含复现上下文的不可变 JSON 报告。
- 建立检索、模型调用、SSE 安全输出和模型用量遥测，完成固定容量序列。
- 只测量，不修改生产检索参数，不提前执行阶段 7。

## 已完成工作

- 将评测资源迁到拥有读取适配器的 infrastructure 模块；锁定集版本为 `locked-regression-v1`，哈希为 `95593ee6cc94bea2781b5b189fab27b22912c64796ec285cb1c96c22190ab8b0`。
- 新增 150 条优化开发问题，版本为 `optimization-development-v1`，哈希为 `7f9dd06e37a24a0d8bf66cfcb09f4bd89e250256d1d28f0b6fd5252e9161b716`；类别分布为 30/30/30/20/20/20。
- 数据加载时校验版本哈希、数量与分布、重复问题、稳定来源、相关等级、无命中/多轮/冲突标注及答案正文泄漏。
- 评测 API 支持选择锁定集或开发集，报告记录 Git 提交、数据版本/哈希、模型、参数、起止时间、总耗时、五项质量指标和检索延迟。
- 使用 Micrometer 记录检索、Embedding、Rerank、模型首 Token、安全首片段、答案完成和完整请求；标签只包含固定操作与成功/失败。
- 模型用量只记录 Token、文本或候选数量及字符数的样本数、总量、均值、P95、最大值，不记录问题、Prompt、知识或答案正文。
- 新增 `stage-6-baseline` Maven Profile，使用真实 HTTP/SSE、MySQL 8.4.11、Redis 7.4.7、Elasticsearch 9.5.2 与测试专用确定性模型执行固定容量序列。
- 新增真实 DashScope 1/5/20 并发抽样用例；只有目标 Maven 进程显式获得 `DASHSCOPE_API_KEY` 时才执行。

## 评测字段字典

| 字段 | 含义与约束 |
|---|---|
| `caseId` | 稳定用例编号，不因排序或数据库 ID 改变 |
| `query` | 实际送入检索的问题；不得包含整段答案 |
| `relevantSourceKeys` | 人工标注的相关来源稳定键列表 |
| `expectedStatus` | 期望三态：有可靠知识、无可靠知识或检索失败 |
| `requiredExactTerms` | 前五候选必须完整保留的错误码、命令、版本等精确词 |
| `category` | `DIRECT`、`NOISY`、`EXACT`、`MULTI_TURN`、`NO_HIT` 或 `CONFLICT` |
| `relevanceGrades` | sourceKey 到 1～3 相关等级的映射，3 表示最相关 |
| `shouldBeNoHit` | 是否应返回无可靠知识，必须与期望状态一致 |
| `requiresMultiTurnContext` | 是否属于依赖上文改写的测试问题 |
| `hasKnowledgeConflict` | 文档与案例是否包含需披露的知识冲突 |
| `schemaVersion` | 报告结构版本，不是数据集版本 |
| `gitCommit` | 运行时工作树 HEAD 的完整提交哈希；本次执行时工作树尚未提交，需结合数据哈希复现 |
| `startedAt` / `finishedAt` / `durationMs` | UTC 起止时间与单调时钟测得的整次运行毫秒数 |
| `successCount` / `failureCount` | 指定操作成功与失败的样本数量 |
| `average` / `p95` / `maximum` | 单次用量的平均、95 分位和最大值 |

## 实测基线与差距

确定性质量基线使用真实 Elasticsearch 和固定字符二元组 Embedding/Rerank，仅衡量可重复的检索链路，不代表真实 DashScope 模型效果。

| 数据集 / `HYBRID_RERANK` | Recall@5 | MRR@10 | nDCG@5 | 无命中准确率 | 精确词召回 |
|---|---:|---:|---:|---:|---:|
| 锁定回归集 | 0.933333 | 0.902469 | 0.908729 | 0.40 | 1.00 |
| 优化开发集 | 1.00 | 0.992308 | 0.994322 | 0.75 | 0.966667 |

- 阶段 7 的锁定集有效下限取较高值：Recall@5 0.933333、MRR@10 0.902469、nDCG@5 0.908729、无命中准确率 1.00、精确词召回 1.00。
- 首要差距是无命中误召回；开发集还存在 1 条精确词问题未达到 1.00。阶段 7 应优先校准可靠知识门槛和精确词保护，再比较中文分析、分块与排序参数。
- 四模式完整结果保存在 `support-agent-infrastructure/target/stage-6-baseline/quality-*.json`，动态报告不交给 Git 管理。

容量基线共 826 个计入场景的请求，另有 5 次预热，0 个失败：单用户 P95 49 ms；持续 2 RPS 共 600 请求，P95 39 ms；5 RPS 30 秒共 150 请求，P95 33 ms；20 并发三轮均为 100% 成功，最高 P95 88 ms。安全首片段内部 P95 约 21.50 ms，答案完成 P95 约 24.12 ms。以上延迟使用确定性模型，只证明本地应用与基础设施容量，不代表公网模型延迟。

真实在线抽样使用 `qwen3.7-flash-2026-07-15`：1、5、20 并发批次成功率均为 100%，P95 分别为 301 ms、735 ms、842 ms，20 并发最大耗时 944 ms；共计输入 1311 Token、输出 246 Token。该结果用于验证第三方连接、并发和限流表现，不替代本地持续负载结论。

## 文件与契约变化

| 路径或对象 | 变化类型 | 变化内容及含义 |
|---|---|---|
| `support-agent-application` | 新增/修改 | 数据集用途、快照、运行上下文、延迟摘要、稳定来源指标及低基数遥测端口 |
| `support-agent-agent` | 修改/新增测试 | Chat/Embedding/Rerank 聚合用量与耗时采集；真实模型并发抽样 |
| `support-agent-infrastructure` | 新增/修改 | 严格数据治理、Git HEAD 解析、不可覆盖报告及两套固定数据集 |
| `support-agent-interfaces` | 修改 | `dev/test` 评测接口增加数据集选择、复现上下文、稳定来源和耗时字段 |
| `support-agent-bootstrap` | 新增/修改 | Micrometer 适配、模块装配及完整 HTTP/SSE 容量基线 |
| 根 `pom.xml` | 修改 | 新增独立 `stage-6-baseline` Profile；常规 integration 排除耗时基线 |

## Review 记录

| 字段 | 内容 | 字段含义 |
|---|---|---|
| Review 时间 | 2026-09-09 21:20 | 完整差异最后检查时间 |
| Review 范围 | 阶段 6 全部代码、资源、测试、报告字段、Maven Profile、未跟踪文件；另将阶段开始前已有的优化计划文档差异单独识别 | 实际检查边界 |
| Review 结论 | 通过 | 当前阶段差异满足提交门禁，可按用户后续指令提交 |
| 发现问题 | 评测依赖旧自增 ID；资源归属模块错误；冲突问题使用机械场景后缀；Chat 请求耗时重复计数；安全首片段在实际发送前计时；工单 Agent 将调用开始误记为首 Token；容量报告先后缺少耗时 P95、用量均值/P95、运行起止信息和独立单用户场景；检索代码缩进不一致；两份 JSONL 末尾存在多余空行 | Review 实际发现项 |
| 处理结果 | 引入 sourceKey 与哈希清单；迁移资源；改写自然问题；统一应用层计时并在真实 delta 后记录；工单分支只续租不伪报首 Token；补齐全部报告字段和单用户基线；修正格式；删除 JSONL 多余空行并同步冻结哈希；重新编译、测试和完整压测 | 所有发现均已处理并复查 |

## 测试与验证

| 命令或检查 | 结果 | 含义与备注 |
|---|---|---|
| `mvn clean test` | 通过 | 109 项单元、架构、契约和数据治理测试，0 失败、0 错误、0 跳过 |
| `mvn verify -Pintegration` | 通过 | 15 项基础设施 IT、5 项完整应用 IT 通过；覆盖真实 MySQL、Redis、Elasticsearch、Flyway 与启动契约 |
| `mvn verify -Pstage-6-baseline` | 通过 | 最终完整运行 6 分 58 秒；质量重复一致，单用户、固定 5 分钟持续负载与 30 秒峰值全部通过 |
| `mvn verify -Ponline-test` | 通过 | 5 项真实 DashScope 用例全部执行，0 失败、0 错误、0 跳过；覆盖 Chat、Embedding、Rerank 和 1/5/20 并发抽样 |
| 编译失败复查 | 已修复 | 用量 P95 报告首次编译因 `Optional<Double>` 默认值类型错误失败；改为 `0.0` 后 test-compile 与完整基线通过 |
| `git diff --check` | 通过 | 无空白错误；只有 Windows 工作区 LF/CRLF 提示 |
| 敏感信息与遗留项扫描 | 通过 | 未发现真实密钥、硬编码密码、调试输出、TODO 或完整业务正文日志 |

## 风险、限制与未完成项

- 真实 DashScope 结果只是一轮小样本并发抽样，不代表供应商长期稳定性或持续吞吐；持续负载结论仍以确定性模型的本地容量基线为准。
- 工单 AgentScope 分支当前只能在完整响应返回后续租，无法提供真实流式首 Token，因此不写入 `MODEL_FIRST_TOKEN`；普通问候与 RAG 流式分支按首个模型文本块记录。
- SpringDoc/Jackson Schema 兼容性警告与 Testcontainers 停止 Redis 后的 Lettuce 重连警告仍存在，均为前阶段已知事项，不影响本阶段断言；按计划留到阶段 9 处理。
- 本阶段未修改任何生产检索参数、模型选择、业务状态、数据表或安全契约。

## 下一阶段建议

- 到此停止，不自动执行阶段 7，不提交或推送。待用户确认后，阶段 7 应使用优化开发集实验，并始终以锁定回归集有效门槛做最终回归。
