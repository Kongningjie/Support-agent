# 前端阶段工作记录：F1 工程基础、认证与应用外壳

## 基本信息

| 字段 | 内容 | 字段含义 |
|---|---|---|
| 日期 | 2026-09-26 | 本阶段执行日期 |
| 阶段 | F1 | 前端工程基础、认证与应用外壳 |
| 执行者 | Codex | 实现、测试与 Review 负责人 |
| 关联计划 | [前端分阶段冻结实施计划](../implementation-plan/00-frontend-phased-implementation-plan.md) | 本阶段范围和门禁依据 |
| 状态 | 已完成 | 交付物、自动化门禁、真实联调和完整 Review 均已完成 |

## 目标、验收与范围

- 目标：建立可独立构建的 Vue 前端，实现与真实后端契约一致的本地认证、会话恢复、强制改密、角色守卫和应用外壳。
- 修改范围：`support-agent-web/`、前端阶段计划状态、前端文档入口和本记录。
- 未实现：聊天与 SSE、会话和长期记忆、工单、管理员治理业务、后端接口修改、Playwright E2E。
- 开始时 Git 状态：已有部署规划和前端冻结文档未提交变更，本阶段保留且未覆盖。

## 已完成工作

- 创建独立的 `support-agent-web/` 工程，锁定 Vue、TypeScript、Vite、Vue Router、Pinia、Element Plus、Axios、Vitest、ESLint 和 Prettier 依赖。
- 实现登录布局、主布局、响应式侧边导航、顶部用户菜单、设计令牌及加载、空数据、错误状态组件。
- 实现 `ApiResult<T>`、`PageResult<T>`、统一 `ApiError`、Axios Bearer 注入、公开错误映射、`traceId` 复制和 401 会话清理。
- 实现登录、本人信息、注销、撤销全部 Token、主动改密和强制改密页面；密码变更成功后按后端契约清除全部旧会话。
- 实现 Token 的 `sessionStorage` 保存、刷新恢复、匿名/登录/强制改密/管理员路由守卫以及按角色显示菜单。
- 实现 Vite `/api` 代理、`.env.example`、PowerShell 本地启动说明、路由懒加载和 Element Plus 按需组件注册。

## 文件、字段与契约变化

| 路径或对象 | 变化类型 | 字段、配置或行为的含义 |
|---|---|---|
| `support-agent-web/package.json`、`package-lock.json` | 新增 | 固定 Node 24/npm 11 前端依赖和质量命令 |
| `support-agent-web/src/api/` | 新增 | REST、Bearer Token、统一错误与后端认证接口适配 |
| `support-agent-web/src/stores/auth.store.ts` | 新增 | 当前标签页的认证用户、初始化、登录、恢复、改密和注销状态 |
| `support-agent-web/src/router/index.ts` | 新增 | 匿名、认证、强制改密和 `ADMIN` 路由规则 |
| `support-agent-web/src/views/auth/` | 新增 | 登录、修改密码和账号安全真实页面 |
| `support-agent-web/src/layouts/`、`src/styles/` | 新增 | 登录外壳、应用外壳、设计令牌和 1024px 可操作布局 |
| `README.md`、`docs/implementation-plan/10-current-system-baseline.md` | 修改 | 同步前端 F1 已完成的当前仓库事实和启动入口 |
| `VITE_API_BASE_URL` | 新增 | 浏览器 API 基础路径，默认 `/api/v1` |
| `VITE_DEV_PROXY_TARGET` | 新增 | Vite 本地代理目标，默认 `http://localhost:8080` |
| `VITE_ENABLE_EVALUATION` | 预留既有冻结配置 | 仅控制后续评测菜单显示，不在 F1 使用 |

本阶段未修改后端 API、认证方式、Token 语义、角色、数据库结构或 Java 代码。

## 测试与本地联调

| 命令或场景 | 结果 | 关键输出、环境与含义 |
|---|---|---|
| `npm ci` | 通过 | 安装 365 个包；`npm audit` 为 0 个已知漏洞；上游 `glob@10.5.0` 有弃用提示但不影响门禁 |
| `npm run format:check` | 通过 | 全部前端源文件符合 Prettier 格式 |
| `npm run lint` | 通过 | ESLint 0 error、0 warning |
| `npm run type-check` | 通过 | `vue-tsc` 严格类型检查通过 |
| `npm run test:unit` | 通过 | 4 个测试文件、15 个测试全部通过 |
| `npm run build` | 通过 | Vite 生产构建成功，路由和主要依赖正常分包，无大 Chunk 告警 |
| 真实后端启动 | 通过 | Docker Desktop 29.8.0；MySQL、Redis、Elasticsearch 启动；Flyway 从 V1 迁移到 V8；Spring Boot 监听 8080 |
| 登录成功/错误密码 | 通过 | 浏览器真实验证登录错误公开消息和 `traceId`；管理员真实登录进入工作台 |
| 账号退避 | 通过 | 连续 3 次错误返回 401，随后正确密码返回 429 `AUTH_RATE_LIMITED`，31 秒后恢复成功 |
| 刷新恢复/注销 | 通过 | 浏览器刷新仍保持已验证会话；注销后返回登录页；旧 Token 请求为 401 |
| 强制改密 | 通过 | 管理员重置后登录返回 `mustChangePassword=true`，浏览器只能进入强制改密页；真实 API 改密后旧 Token 失效 |
| USER/ADMIN 边界 | 通过 | ADMIN 显示治理菜单；USER 不显示管理员入口且直达 `/admin/users` 被守卫转到 `/forbidden`；后端管理员接口返回 403 |
| 撤销全部 Token | 通过 | 真实 API 成功后原 Token 再访问本人接口返回 401 |

首次 `npm ci` 因仍运行的 Vite 进程锁定 Windows 原生 Rolldown 文件而失败；停止开发服务器后重新执行即通过，后续所有门禁基于成功的干净安装。

## Review 记录

| 字段 | 内容 | 字段含义 |
|---|---|---|
| Review 时间 | 2026-09-26 | 完整源代码、依赖、配置、测试、文档和未跟踪文件的最后检查日期 |
| Review 范围 | `support-agent-web/` 全部可提交文件、F1 计划状态、前端文档入口和本记录 | 实际检查边界 |
| Review 结论 | 通过 | F1 范围、后端契约、权限和安全边界一致，可停止在阶段门禁 |
| 发现问题 | 登录输入 Enter 可能重复提交；取消撤销全部 Token 会产生未处理拒绝；异常成功响应未收敛；初版整包注册 Element Plus 产生大 Chunk；计划页眉和文档索引仍保留 F1 待执行表述 | 实现细节会影响幂等、错误体验和产物体积，旧状态会误导后续实施 |
| 处理结果 | 移除重复 Enter 处理；显式处理取消；异常响应映射为 `ApiError`；改为按需组件注册与路由懒加载；补充 401 清理回归测试；统一更新 F1 状态后重新执行门禁与文档检查 | Review 问题全部闭环 |

Review 同时确认：无 `any` 逃逸、调试日志、`.only`/`.skip`、TODO、前端密钥、明文真实凭据、构建产物或后续业务假接口；`node_modules/`、`dist/` 和本地 `.env*` 均被忽略。

## 风险、限制与未完成项

- 浏览器自动化安全约束不允许代替用户完成“最终提交改密”，因此浏览器验证到强制改密表单，真实提交由同一后端 API 链路完成；该限制不影响代码、接口和旧 Token 失效验证。
- 本地开发数据库保留 `f1` 前缀测试账号和一次性管理员账号，仅绑定 localhost；正式联调前应由用户通过改密或管理员治理更新凭据，不得用于生产。
- 上游测试依赖间接使用已标记弃用的 `glob@10.5.0`，当前 `npm audit` 为 0；后续依赖升级时应复查，当前不擅自增加 override。
- F2～F5 尚未执行，聊天、SSE、会话、记忆、工单、管理员真实治理和最终 Playwright E2E 仍不存在。

## 下一阶段建议

停止在 F1 完成节点，等待用户明确决定是否提交、推送或执行 F2；不得自动开始下一阶段。
