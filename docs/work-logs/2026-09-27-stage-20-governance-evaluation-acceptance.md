# 阶段工作记录：阶段 20 空间权限治理、评测与四期验收

## 基本信息

| 字段 | 内容 | 字段含义 |
|---|---|---|
| 日期 | 2026-09-27 | 本阶段开始日期 |
| 阶段 | 20 | 空间权限治理、评测与四期验收 |
| 执行者 | Codex | 实现、测试与 Review 负责人 |
| 关联计划 | [四期知识空间实施计划](../implementation-plan/12-phase-4-knowledge-space-plan.md) | 当前阶段范围与门禁依据 |
| 关联冻结方案 | [四期知识空间冻结方案](../design-freezes/04-phase-4-knowledge-space.md) | 不得改变的业务与架构语义 |
| 状态 | 已完成 | 2026-09-28 通过全部离线、集成、客户端、索引完整性和 Review 门禁 |

## 目标与范围

- 补齐成员撤权、角色降低、用户禁用和空间停用后的即时权限收敛与并发安全。
- 建立不少于 60 条固定中文空间隔离评测数据及可重复的动态报告。
- 完成低基数指标、安全审计、显式空间契约、HTTP 示例和运维恢复手册。
- 执行单元、基础设施集成、完整性核对、前端客户端回归、敏感信息扫描和完整差异 Review。
- 不执行未获单独授权的真实 DashScope 在线测试，不进入下一产品期次或延期部署。

## 开始状态

- 当前分支为 `main`，开始时工作区无未提交变更。
- 阶段 18～19 和前端 F3S 已完成并推送，正式客户端已具备显式空间选择能力。
- 阶段 19 已通过 258 个单元测试和 53 个基础设施集成测试；真实模型范围化问答未获单独在线授权。

## 实施、测试与 Review

### 已完成实现

- 新会话、手工工单和知识草稿不再缺省绑定 `GLOBAL`，缺少 `spaceId` 时返回 `KNOWLEDGE_SPACE_CONTEXT_REQUIRED`；阶段 19 前的无空间 Redis 会话仍只在原 7 天 TTL 内兼容读取。
- Chat 在答案正文和引用发送前重新读取账号、空间及成员事实；撤权、降级、账号禁用或空间停用后走安全错误终止，不发送未授权正文。
- 空间访问与索引重建新增低基数 Micrometer 指标和最小安全事件；Review 发现指标值 `ALLOWED` 不能直接写入安全事件表，已转换为冻结值 `SUCCEEDED` 并增加回归测试。
- 增加 60 条固定中文 JSONL 隔离用例、严格数据集校验、聚合门槛计算和 `target/knowledge-space-evaluation/` 动态报告。当前验收使用确定性受控观测验证评测框架和冻结预期，不代表真实模型在线隔离效果。
- 增加只读 MySQL/Elasticsearch 一致性核对脚本、知识空间运行恢复手册、HTTP 示例以及领域、API、RAG、工程专题增量说明。

### 已执行测试

| 命令或检查 | 结果 | 说明 |
|---|---|---|
| `mvn test` | 通过 | 共 272 项：领域 28、应用 167、Agent 22、基础设施单元 10、接口 23、Bootstrap 22；0 失败、0 错误、0 跳过 |
| `mvn verify -Pintegration` | 通过 | 基础设施集成 44 项、应用启动联调 9 项；0 失败、0 错误、0 跳过 |
| 前端冻结门禁 | 通过 | `format:check`、Lint、类型检查、15 个文件 44 项单测、生产构建全部通过 |
| `npm audit --audit-level=high` | 通过 | 0 个漏洞 |
| PowerShell AST 解析 | 通过 | `verify-knowledge-space-consistency.ps1` 无语法错误 |
| `mvn verify -Ponline-test` | 未执行 | 未获得本阶段真实 DashScope 在线测试授权 |

集成门禁首次执行时 Docker Desktop 引擎尚未就绪，容器启动前失败；引擎恢复后完整重跑通过。首次应用启动联调还发现空间访问审计把 `ALLOWED` 写入只接受 `SUCCEEDED/DENIED` 的数据库字段，修复映射并补回归测试后通过。

### 本地索引核对与处置

首次在本地 Compose 存量环境执行只读一致性脚本时，检测到 `MANAGED_DOCUMENT:1` 已在 MySQL 标记为 `PUBLISHED`，但 Elasticsearch v1 中缺少对应来源。用户随后单独授权本次 Embedding 调用；正式管理员重建接口使用 `support_knowledge_v2` 完成全量分块、Embedding、完整性校验和原子别名切换。最终只读复核通过：1 个已发布来源、8 个分块的空间和版本一致，业务别名指向 v2，旧 v1 未删除并保留回切边界。

重建前还发现本地 `.env` 的 `SUPPORT_AGENT_KNOWLEDGE_INDEX` 仍指向正在服务的 v1，应用按安全规则拒绝原地重建。执行时仅临时覆盖为 v2；用户随后已将本地 `.env` 修正为 `support_knowledge_v2`。`.env` 未纳入 Git，凭据和 Token 未进入日志或阶段记录。本次授权只覆盖 Embedding 重建，未执行 Chat、Rerank 或完整 `online-test`。

### Review 状态

- 已完成业务契约、权限收敛、指标标签、评测报告、运维脚本、测试和文档的完整差异 Review。
- 已处理安全审计结果枚举不兼容、旧测试缺少显式空间、本地索引目标配置陈旧和运行手册排版问题，修复后重新执行相关测试和门禁。
- `git diff --check` 通过；敏感信息扫描未发现 API Key、私钥、Bearer Token 或其他真实凭据，`.env` 继续被 Git 忽略。
- 阶段 20 规定交付物、固定评测、前后端回归、MySQL/Elasticsearch 完整性核对和 Review 均已通过；四期关闭，不提交、不推送、不进入下一阶段。
