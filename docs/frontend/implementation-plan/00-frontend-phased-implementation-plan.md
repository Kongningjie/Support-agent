# Support Agent 前端分阶段冻结实施计划

> 冻结日期：2026-09-26；状态：方案已冻结，阶段 F1 已完成，F2～F5 待执行。

## 1. 目标与边界

本计划为现有 Support Agent 后端补充一个可实际使用、可本地联调、可自动化验证的企业内部 Web 客户端。前端只呈现当前已经存在的认证、聊天、会话、长期记忆、工单和管理员能力，不新增后端业务状态、接口或权限语义。

最终目标是在 Windows 11 本机同时启动后端和前端，使用真实 MySQL、Redis、Elasticsearch 及已授权 DashScope 配置，完成从登录、AI 问答、工单闭环到管理员知识治理的端到端验证。

本期不实现：

- 智能工单协同、分派、接单、评论、SLA 或通知，因为后端尚无对应契约。
- 多租户、组织架构、复杂 RBAC、SSO/OIDC、注册、找回密码。
- 移动端 App、微信小程序、Electron、服务端渲染或微前端。
- 前端直连 DashScope、MySQL、Redis 或 Elasticsearch。
- 公网部署、HTTPS、CDN、前端监控平台和 CI/CD。
- 用固定假数据、静态页面或未接接口按钮冒充阶段完成。

## 2. 冻结技术栈

| 项目 | 冻结选择 | 含义与约束 |
|---|---|---|
| 运行环境 | Node.js 24.x LTS、npm 11.x | 与当前本地 Node 24 环境一致；使用 `package-lock.json` 锁定实际依赖 |
| 框架 | Vue 3 + TypeScript | 统一使用 Composition API 和 `<script setup lang="ts">` |
| 构建工具 | Vite | 提供开发服务器、代理、构建和环境变量注入 |
| 路由 | Vue Router | 负责匿名、已认证、强制改密和管理员页面守卫 |
| 状态管理 | Pinia | 只保存跨页面会话状态；普通列表查询不滥用全局 Store |
| 组件库 | Element Plus | 用于表单、表格、分页、弹窗和反馈；不再叠加第二套组件库 |
| HTTP | Axios | 处理普通 JSON REST、Bearer Token、统一错误和 `traceId` |
| SSE | 原生 `fetch` + `ReadableStream` | 支持 POST、JSON 请求体、Authorization 和主动取消；不使用 `EventSource` |
| Markdown | markdown-it + DOMPurify | 禁用原始 HTML，净化生成结果并限制链接协议后再展示模型答案 |
| 单元测试 | Vitest + Vue Test Utils | 验证 Store、组合函数、SSE 解析、权限和关键组件 |
| 端到端测试 | Playwright | F5 使用真实本地后端验证核心业务链路 |
| 代码质量 | ESLint + Prettier + `vue-tsc` | 锁定格式、静态检查和严格类型检查 |

前端工程固定放在仓库根目录 `support-agent-web/`，不加入 Maven Reactor，也不把 Node 构建塞入后端 Maven 生命周期。本地联调分别运行 Maven 后端和 Vite 前端。

## 3. 工程结构

```text
support-agent-web/
├─ src/
│  ├─ api/             # REST 请求、SSE 客户端和接口类型
│  ├─ assets/          # 本地静态资源
│  ├─ components/      # 可复用展示组件，不承载页面业务编排
│  ├─ composables/     # 可复用状态逻辑和浏览器能力封装
│  ├─ layouts/         # 登录布局和主应用布局
│  ├─ router/          # 路由表、权限元数据和守卫
│  ├─ stores/          # 认证、聊天等跨页面状态
│  ├─ styles/          # 设计令牌、基础样式和 Element Plus 覆盖
│  ├─ types/           # 共享 TypeScript 类型
│  ├─ views/           # 按业务能力组织的路由页面
│  ├─ App.vue
│  └─ main.ts
├─ tests/
│  ├─ unit/            # Vitest 单元和组件测试
│  └─ e2e/             # Playwright 真实联调测试
├─ .env.example
├─ package.json
├─ package-lock.json
├─ tsconfig.json
├─ vite.config.ts
└─ README.md
```

不得建立无边界的 `common/` 或 `utils/`。页面按 `auth`、`chat`、`conversation`、`memory`、`ticket`、`user-admin`、`knowledge`、`resolved-case`、`async-task`、`evaluation` 组织。

## 4. 配置与本地联调

### 4.1 前端环境变量

| 字段 | 默认值 | 字段含义 |
|---|---|---|
| `VITE_API_BASE_URL` | `/api/v1` | 浏览器请求的后端 API 基础路径；不得包含密钥或用户信息 |
| `VITE_DEV_PROXY_TARGET` | `http://localhost:8080` | 仅由 Vite 开发代理使用的本地后端地址 |
| `VITE_ENABLE_EVALUATION` | `false` | 是否显示仅在后端 `dev/test` Profile 存在的检索评测入口 |

`.env.example` 只保存非敏感占位值。所有 `VITE_*` 变量都会进入浏览器产物，严禁放入 DashScope API Key、密码、Bearer Token 或数据库凭据。

Playwright 使用的 `E2E_BASE_URL`、`E2E_USER_USERNAME`、`E2E_USER_PASSWORD`、`E2E_ADMIN_USERNAME` 和 `E2E_ADMIN_PASSWORD` 只能作为测试进程环境变量注入，不能使用 `VITE_` 前缀，也不能提交真实值。E2E 只能连接本地 `dev/test` 环境，严禁指向生产地址；使用预先创建的固定测试账号，业务数据使用 `e2e-<时间或随机标识>` 前缀，并通过现有归档、关闭或删除接口完成可执行的清理。

### 4.2 联调拓扑

```text
浏览器 http://localhost:5173
        │ /api/*
        ▼
Vite 开发代理
        │ http://localhost:8080/api/*
        ▼
Spring Boot 后端 → MySQL / Redis / Elasticsearch / DashScope
```

本地开发优先使用 Vite 同源代理，因此第一版不修改后端 CORS。若真实构建产物需要由其他端口独立托管，必须先单独确认部署方式和后端安全白名单。

## 5. 身份、权限与安全边界

- 登录返回的不透明 Bearer Token 只保存在 `sessionStorage` 和当前 Pinia 内存状态，不写入 `localStorage`、URL、日志或错误报告。
- 页面刷新时可从 `sessionStorage` 恢复 Token，再调用 `GET /users/me` 恢复用户状态；验证失败立即清空会话并回到登录页。
- `401` 表示认证失效，清理 Token 并跳转登录；`403` 显示无权限；`429` 展示服务端公开消息，不自行推算账号锁定细节。
- `mustChangePassword=true` 时，只允许本人信息、修改密码和注销页面，路由守卫必须阻止进入其他页面。
- `USER` 与 `ADMIN` 菜单控制只改善体验，不是安全边界；所有请求仍由后端完成权限和资源归属校验。
- 前端不得渲染未经净化的 HTML。模型 Markdown 统一由 markdown-it 在 `html=false` 下解析，再经 DOMPurify 净化；链接协议只允许 `https`、`http` 和站内相对地址，禁止 `javascript:`、`data:` 等危险协议。
- 不展示后端堆栈、内部类名或原始第三方错误。错误提示保留 `traceId` 并提供复制入口，便于排查。

## 6. 通用客户端契约

### 6.1 JSON 响应

| 类型或字段 | TypeScript 语义 | 字段含义 |
|---|---|---|
| `ApiResult<T>.code` | `string` | 稳定业务结果码；成功为 `SUCCESS` |
| `ApiResult<T>.message` | `string` | 可向用户展示的简体中文说明 |
| `ApiResult<T>.data` | `T \| null` | 成功数据或允许公开的错误详情 |
| `ApiResult<T>.traceId` | `string` | 当前请求追踪标识，错误时允许复制 |
| `ApiResult<T>.timestamp` | `string` | 服务端 UTC ISO-8601 时间，不在传输层转成本地字符串 |
| `PageResult<T>.items` | `T[]` | 当前页条目 |
| `PageResult<T>.page` | `number` | 从 1 开始的当前页码 |
| `PageResult<T>.size` | `number` | 当前每页数量，最大 100 |
| `PageResult<T>.totalElements` | `number` | 满足条件的总条数 |
| `PageResult<T>.totalPages` | `number` | 按当前页大小计算的总页数 |

所有后端 ID 在前端按 `string` 处理，不尝试转成 JavaScript `number`。所有时间保留原始 UTC 字符串，展示层再按浏览器时区格式化。

### 6.2 请求状态

| 状态 | 含义 | 页面行为 |
|---|---|---|
| `idle` | 尚未请求 | 展示初始界面 |
| `loading` | 首次加载 | 展示骨架或局部加载，不重复提交 |
| `success` | 成功获得有效数据 | 渲染数据并关闭加载状态 |
| `empty` | 请求成功但无数据 | 展示明确空状态和可执行入口 |
| `error` | 请求失败 | 展示公开消息、重试入口和可复制 `traceId` |

对写操作必须防止按钮重复点击。需要 `idempotencyKey` 或 `clientMessageId` 的操作由浏览器使用 `crypto.randomUUID()` 生成；网络结果不确定时重试同一业务动作必须复用原 Key，不得每次点击都生成新 Key。

## 7. 页面与权限范围

| 路由 | 页面职责 | 允许角色 |
|---|---|---|
| `/login` | 用户名密码登录、登录退避提示 | 匿名 |
| `/change-password` | 强制改密或主动修改本人密码 | `USER`、`ADMIN` |
| `/chat` | 新会话、多轮问答、SSE 状态、引用和工单建议 | `USER`、`ADMIN` |
| `/conversations/:id` | 查看最近成功轮次，并继续当前会话 | `USER`、`ADMIN`，后端校验归属 |
| `/tickets` | 工单筛选、分页、手工建单 | `USER`、`ADMIN` |
| `/tickets/:ticketNo` | 工单详情、当前状态允许的编辑与流转 | `USER`、`ADMIN`，后端校验归属 |
| `/memories` | 长期记忆开关、候选确认、修改、撤销、删除 | `USER`、`ADMIN`，仅本人 |
| `/account` | 本人摘要、改密、撤销全部 Token、注销 | `USER`、`ADMIN` |
| `/admin/users` | 创建用户、角色/状态、重置密码、解锁、撤销 Token | `ADMIN` |
| `/admin/knowledge` | 文本/文件导入、查询、编辑、发布、归档、删除 | `ADMIN` |
| `/admin/resolved-cases` | 案例查询、编辑、发布、拒绝、归档 | `ADMIN` |
| `/admin/async-tasks` | 任务查询、筛选、详情和 DEAD 任务人工重试 | `ADMIN` |
| `/admin/evaluations` | 发起和查看本地检索评测 | `ADMIN` 且功能开关开启 |
| `/forbidden`、`/:pathMatch(.*)*` | 无权限和页面不存在 | 全部 |

第一版不创建无后端数据来源的统计大屏。主布局首页默认跳转 `/chat`；管理员通过侧边导航进入治理页面。

## 8. Chat SSE 冻结行为

聊天请求使用 `POST /api/v1/chat/stream`，携带 JSON 请求体、`Accept: text/event-stream` 和 Bearer Token。客户端必须按 SSE 帧解析 `event`、`id` 和多行 `data`，不能假设一次网络 Chunk 等于一个事件。

| 事件 | 前端行为 |
|---|---|
| `conversation.started` | 记录 `conversationId`、`runId`，把本轮状态置为运行中 |
| `retrieval.started` | 显示“正在检索企业知识”进度，不伪造百分比 |
| `retrieval.completed` | 记录检索三态，显示可靠知识、无可靠知识或检索故障提示 |
| `answer.started` | 创建助手消息容器，准备接收安全片段 |
| `answer.delta` | 按 `sequence` 追加安全文本片段，不把片段当作 HTML |
| `citation` | 按 `citationId` 去重并关联答案引用卡片 |
| `ticket.suggested` | 展示用户主动确认的“创建工单草稿”按钮 |
| `answer.completed` | 以完整答案和新会话版本覆盖聚合结果，结束运行状态 |
| `error` | 终止本轮，展示稳定错误和是否可重试，不保留半成品为成功回答 |

使用 `AbortController` 支持用户取消和页面卸载。当前协议不支持 `Last-Event-ID`，因此断流后不得自动创建新消息重试。若用户确认重试同一条消息，复用原 `clientMessageId`，让后端决定重放完成结果或返回冲突。

## 9. 分阶段实施

阶段状态只能使用 `待执行`、`进行中`、`已完成`、`受阻`。

| 阶段 | 名称 | 状态 | 核心结果 |
|---|---|---|---|
| F1 | 工程基础、认证与应用外壳 | 已完成 | 可登录、恢复会话、强制改密并按角色进入真实页面外壳 |
| F2 | AI 对话、会话与长期记忆 | 待执行 | POST SSE、多轮会话、引用、工单建议和记忆治理可用 |
| F3 | 工单用户业务闭环 | 待执行 | 工单列表、详情、建单和既有状态流转可用 |
| F4 | 管理员治理工作台 | 待执行 | 用户、知识、案例、任务和本地评测管理可用 |
| F5 | 本地联调、质量补强与验收 | 待执行 | 真实后端端到端链路、错误场景和构建门禁全部通过 |

### 9.1 阶段 F1：工程基础、认证与应用外壳

必须交付：

- 创建 `support-agent-web/`，锁定 npm 依赖、严格 TypeScript、ESLint、Prettier、Vitest 和基础构建命令。
- 建立设计令牌、Element Plus 主题、登录布局、主布局、侧边导航、顶部用户菜单和通用空/错/加载状态。
- 实现 Axios 实例、`ApiResult`/`PageResult` 类型、Bearer 注入、统一错误对象和 `traceId` 复制。
- 实现登录、本人信息、注销、撤销全部 Token、主动改密与强制改密流程。
- 实现 Token `sessionStorage` 持久化、刷新恢复、401 清理、角色路由守卫和管理员菜单。
- 提供 `.env.example`、PowerShell 启动说明和 Vite `/api` 代理。

门禁：

```powershell
Set-Location .\support-agent-web
npm ci
npm run lint
npm run type-check
npm run test:unit
npm run build
```

使用真实本地后端人工验证：登录成功、错误密码、账号退避、刷新恢复、强制改密、USER 越权路由、ADMIN 菜单和注销。

### 9.2 阶段 F2：AI 对话、会话与长期记忆

必须交付：

- 实现健壮的 POST SSE 解析器、序号检查、取消、断流、错误事件和幂等重试。
- 实现新会话和后续会话、多阶段进度、完整答案、引用卡片、工单建议按钮及安全文本展示。
- 实现会话列表、详情、继续对话、重置、删除、版本冲突刷新和过期处理。
- 实现长期记忆开关、分页、状态筛选、候选确认、正文修订、固定/失效时间、撤销和永久删除。
- 对 SSE Parser、跨 Chunk/多行 data、乱序/重复事件、取消、会话版本更新和权限错误增加测试。

门禁除 F1 命令外，必须使用真实后端验证问候、RAG 引用回答、无可靠知识建议、Prompt 注入阻断、会话重置/删除和长期记忆完整操作。不授权真实 DashScope 时只能记录为未验证，不能宣称 F2 完成。

### 9.3 阶段 F3：工单用户业务闭环

必须交付：

- 实现工单列表、状态/关键字筛选、分页和详情。
- 实现手工草稿，以及从 F2 的有效 `ticket.suggested` 创建草稿。
- 根据后端真实状态显示编辑、提交、解决和关闭操作；所有表单说明字段含义和长度限制。
- 写操作使用乐观锁版本与幂等键，处理 409 冲突时保留用户输入并提供刷新选择。
- 普通用户只展示自己的工单；管理员可查看全部，但前端不推断后端未返回的数据范围。

门禁除通用命令外，必须真实验证两条路径：手工建单到关闭，以及 AI 建议建单到提交、管理员解决并生成异步案例任务。

### 9.4 阶段 F4：管理员治理工作台

必须交付：

- 用户治理：创建、筛选、角色、状态、一次性密码重置、解锁、撤销全部 Token。
- 知识治理：文本导入、Markdown/TXT 上传、详情、草稿编辑、发布、归档和草稿删除。
- 案例治理：筛选、详情、编辑、发布、拒绝和归档。
- 异步任务：筛选、详情、失败摘要和 DEAD 任务人工重试。
- 本地评测：仅在 `VITE_ENABLE_EVALUATION=true` 时展示，支持启动和查看内存评测结果；后端接口不存在时明确隐藏而非报错循环。
- 状态操作按钮必须由后端状态决定，并在确认弹窗中说明不可逆影响。

门禁除通用命令外，必须真实验证 USER 无法访问管理员接口，以及 ADMIN 的用户、知识发布/归档、案例发布和任务重试路径。

### 9.5 阶段 F5：本地联调、质量补强与验收

必须交付：

- 修复 F1～F4 真实联调发现的前端问题；若发现后端契约缺陷，先记录并请求确认，不得擅自扩展后端。
- 补齐 400、401、403、404、409、410、422、429、502、503、网络中断和 SSE 中断体验。
- 完成键盘操作、焦点、表单标签、颜色对比、窄屏不溢出和敏感信息检查。
- Playwright 覆盖登录/改密、聊天、建议建单、工单、会话、记忆和管理员知识治理核心链路。
- 完成 README、运行说明、联调账号准备说明、页面截图和最终 Review。

最终门禁：

```powershell
Set-Location .\support-agent-web
npm ci
npm run lint
npm run type-check
npm run test:unit
npm run build
npm run test:e2e

Set-Location ..
mvn test
mvn verify -Pintegration
```

真实 DashScope 聊天联调必须获得 API Key 和费用授权。未执行的在线验证要记录原因、影响和复现命令。

## 10. 统一 UX 规则

- 桌面端优先，基准宽度 1440px；1024px 仍可完整操作，窄屏允许导航折叠但本期不承诺完整移动端适配。
- 表单必须显示中文标签、必要提示、字符限制和服务端错误，不仅依赖占位符。
- 删除、归档、拒绝、撤销 Token、重置会话等高影响动作必须二次确认。
- 时间默认显示浏览器本地时间，并可查看原始 UTC；状态使用稳定中文映射，提交值仍使用英文枚举。
- 列表筛选变化后回到第 1 页；分页大小只允许 10、20、50、100。
- 不用颜色作为唯一状态表达；所有图标按钮必须有可访问名称。
- 不伪造百分比、模型思考过程、SLA、通知、在线人数或后端未提供的统计数据。

## 11. Review 与完成标准

每个阶段完成前必须：

1. 检查完整 Git 差异和未跟踪文件，保护已有后端与文档变更。
2. 执行本阶段规定的 lint、类型检查、单元测试、构建和真实联调。
3. 检查角色与资源归属、Token、XSS、敏感信息、幂等、版本冲突和 SSE 终止语义。
4. 检查无 `any` 逃逸、无临时日志、无假接口、无禁用测试和无无责任人 TODO。
5. 更新 `docs/frontend/work-logs/` 对应记录，写明文件、字段、测试、Review、风险和未完成项。
6. 停止并等待用户决定是否提交、推送或进入下一阶段。

## 12. 变更控制

以下事项必须先暂停并取得用户确认，再更新冻结方案：

- 替换 Vue、Vite、Pinia、Element Plus、Axios、Vitest 或 Playwright。
- 新增前端状态库、请求缓存框架、富文本编辑器、Markdown 原始 HTML、图表库或第二套 UI 组件库，或者替换已冻结的 Markdown 安全渲染链路。
- 修改后端 API、SSE 事件、认证方式、Token 存储方式、角色或资源归属。
- 新增业务页面、后端能力、跨租户、复杂工单流程或公网部署。

在不改变上述语义的前提下，普通组件拆分、文件命名、CSS 细节和测试数据组织可由实施者自主决定。

## 13. 阶段执行指令

```text
请执行 docs/frontend/implementation-plan/00-frontend-phased-implementation-plan.md 的阶段 FX。
严格遵守 docs/frontend/implementation-plan/01-frontend-engineering-standard.md，只实现本阶段内容。
完成后执行规定测试和完整 Review，更新 docs/frontend/work-logs/ 阶段记录；不要提前执行下一阶段，也不要自动提交或推送。
```
