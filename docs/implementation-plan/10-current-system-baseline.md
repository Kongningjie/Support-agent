# Support Agent 当前系统基线

> 基线日期：2026-09-26；对应代码：后端阶段 17、前端阶段 F1 完成后；用途：后续规划、开发、Review 和 AI Coding 的现状入口。

## 1. 完成状态

- 一期阶段 0～5、二期阶段 6～9、三期阶段 10～14 及阶段 13 补强批次均已完成规定离线与集成门禁。
- 最近门禁结果：`mvn test` 通过 239 个测试；阶段 17 的 `mvn verify -Pintegration` 通过 47 个 MySQL、Redis、Elasticsearch 和应用启动集成测试。
- 阶段 10 的真实在线摘要质量评测按用户决定暂缓；不得据此宣称在线摘要质量已经验收。
- 四期 LLM 安全优化阶段 15～17 已完成规定离线与集成门禁；真实模型对抗验证尚未获得本阶段单独授权，不得宣称已通过在线安全率验收。
- 前端阶段 F1 已完成独立 Vue 工程、登录认证、刷新恢复、强制改密、角色守卫、账号安全和应用外壳；F2～F5 尚未执行。

## 2. 当前能力

系统已经实现托管知识、混合检索、Rerank、带引用 SSE 回答、工单和案例闭环、离线评测、滚动摘要、本地用户认证、资源归属、会话列表/详情/重置/删除、用户可控长期记忆、候选清理与并发治理、账号锁定、改密、管理员用户治理和安全事件审计。独立前端当前能够完成登录、认证恢复、强制改密、本人账号安全和两级角色页面边界，尚未呈现聊天、工单、记忆或治理业务。

当前 LLM 安全能力覆盖三层：阶段 15 为当前消息、历史、摘要、长期记忆、检索证据和工单字段建立确定性 `ALLOW/GUARD/BLOCK` 判定与转义数据区；阶段 16 让问候、RAG、工单回答、工单草稿和案例草稿统一经过 `PASS/REGENERATE/REJECT` 输出网关；阶段 17 新增 80 条固定中文安全数据集、聚合评测报告、策略故障关闭测试，以及只使用来源、动作、信号、模型分支和规则枚举的 Micrometer 指标。离线门禁结果为直接注入阻断率 100%、间接注入上下文逃逸 0、危险输出逃逸 0、困难正常样本误阻断率 0；这些结果只证明确定性策略和应用链路，不代表真实模型对抗效果已经在线验证。

仍不实现多租户、组织架构、复杂 RBAC、RocketMQ、MCP Server、OAuth 授权服务器、真实 OIDC/SSO 或外部身份 SDK。`AuthenticationPort` 与 `ExternalIdentityMappingPort` 只是未来迁移边界。

## 3. 架构与存储

- 仍为 Java 21、Spring Boot 4.1.1 的 Maven 六模块单体，依赖方向为领域层 ← 应用层 ← 适配层，由 Bootstrap 装配。
- 仓库新增独立 `support-agent-web/`：Vue 3、TypeScript、Vite、Vue Router、Pinia、Element Plus 和 Axios；不加入 Maven Reactor，本地通过 Vite `/api` 代理访问后端。
- MySQL 8.4.11 保存业务事实、用户、长期记忆、幂等、任务和安全审计；当前 Flyway 版本为 V8。
- Redis 保存会话、滚动摘要、运行租约、建议、登录失败状态和不透明 Token 哈希索引。
- Elasticsearch 9.5.2 + ICU 承担 BM25、向量、RRF 和 Rerank 前的候选召回。
- DashScope 能力通过 Chat、Intent、Summary、Embedding、Rerank 和长期记忆候选独立端口隔离。

当前默认模型为 Chat `qwen3.8-flash`、Intent `qwen3.7-flash`、Summary `qwen3.7-flash`、Memory `qwen3.7-flash`、Embedding `text-embedding-v4`、Rerank `qwen3-rerank`。模型可通过环境变量覆盖，但不得绕过各自独立端口、安全网关或已冻结的评测边界。

## 4. 身份、权限与账号安全

| 项目 | 当前语义 |
|---|---|
| 认证方式 | 本地用户名密码登录，BCrypt strength 12；不使用 JWT 或 Refresh Token |
| Token | Redis 不透明 Bearer Token，固定 TTL 2 小时；原始值只在登录成功时返回一次 |
| Token 上限 | 每用户最多 5 个有效 Token，第 6 个签发时原子撤销最早 Token |
| 角色 | `USER`、`ADMIN`；管理员不能禁用或降低自己的角色 |
| 资源归属 | 普通用户只能访问自己的会话、工单和长期记忆；管理员管理用户与知识并可查看全部工单 |
| 强制改密 | 管理员重置后 `mustChangePassword=true`；受限 Token 只能查询本人、改密和注销 |
| 登录退避 | 用户名与来源分别计数；第 3 次 30 秒、第 4 次 2 分钟、第 5 次及以上 15 分钟 |
| 安全审计 | MySQL `security_event` 和低基数 Micrometer 指标，不保存用户名、秘密、Token 或请求正文 |

## 5. 会话与记忆

- 会话空闲 TTL 为 7 天，归属于创建用户，并使用 `generation` 隔离重置前后的消息幂等键和建议。
- 上下文通过 Token 预算组装，保留最近 6 个完整轮次；达到软/硬阈值后使用 `qwen3.7-flash` 生成结构化滚动摘要。
- 长期记忆默认关闭。模型只能生成 `PROPOSED` 候选，用户确认后才成为 `ACTIVE` 并进入上下文。
- 记忆类型仅有 `PREFERENCE`、`CONSTRAINT`、`ENVIRONMENT`；单条最多 500 个 Unicode 字符，每用户最多 100 条未删除记录，单次注入预算约 1,000 Token。
- 长期记忆候选模型并发限制为全局 4、每用户 1；30 天未确认候选按每小时最多 500 条清理。

## 6. 当前公共接口分组

| 分组 | 基础路径或代表接口 | 权限 |
|---|---|---|
| 登录与本人安全 | `/api/v1/auth/*`、`/api/v1/users/me*` | 登录匿名；其他为本人 |
| 管理员用户治理 | `/api/v1/admin/users*` | `ADMIN` |
| 聊天与会话 | `/api/v1/chat/stream`、`/api/v1/conversations*` | 已认证且校验所有者 |
| 长期记忆 | `/api/v1/users/me/memory-settings`、`/api/v1/memories*` | 本人 |
| 工单 | `/api/v1/tickets*` | 普通用户仅本人，管理员可查看全部 |
| 知识、案例、任务、评测 | `/api/v1/knowledge*`、`/api/v1/resolved-cases*`、`/api/v1/async-tasks*`、`/api/v1/retrieval-evaluations*` | 管理员；评测仅 `dev/test` |

所有普通 JSON 接口使用 `ApiResult<T>`，分页使用 `PageResult<T>`，页码从 1 开始；SSE 使用独立事件协议。具体字段和请求样例见 `04-api-and-sse.md` 与 `http/`。

## 7. 后续开发约束

- 开始新阶段前必须阅读本文、总计划、强制规范和最近工作记录。
- 历史计划中的范围排除只解释当时阶段；判断当前能力必须以本文、最新三期计划和实际代码为准。
- 不得随意增加中间件、模块、身份供应商、角色、公共接口或业务状态。
- 修改认证、Token、记忆注入、删除语义、模型、数据表或权限边界前，必须先取得用户确认并更新冻结方案。
- 四期阶段 15～17 已按顺序完成；后续不得把真实在线对抗验证的未执行项误写为已通过，也不得绕过现有输入安全策略、输出网关或低基数指标约束。

## 8. 文档事实优先级

判断“当前已经实现什么”时，依次以实际代码与测试、本文、根目录及模块 README、专题文档的最新增量章节为准。冻结计划和旧工作记录用于解释当时决策与证据，不因后续阶段完成而回写历史结论。
