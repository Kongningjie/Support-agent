# 前端阶段工作记录：F4 管理员治理工作台

## 基本信息

| 字段 | 内容 | 字段含义 |
|---|---|---|
| 日期 | 2026-09-28 | 本阶段开始日期 |
| 阶段 | F4 | 管理员治理工作台 |
| 执行者 | Codex | 实现、测试与 Review 负责人 |
| 关联计划 | [前端分阶段冻结实施计划](../implementation-plan/00-frontend-phased-implementation-plan.md) | 本阶段范围与门禁依据 |
| 关联冻结方案 | [四期知识空间冻结方案](../../design-freezes/04-phase-4-knowledge-space.md) | 空间、成员和权限不变量依据 |
| 状态 | 已完成 | 实现、真实在线写链路、受控任务恢复、完整测试和 Review 门禁均已通过 |

## 目标与范围

- 实现管理员用户治理、知识空间和成员治理、托管知识、已解决案例、异步任务及可选本地评测页面。
- 所有页面连接既有真实后端接口，不使用 Mock、固定成功数据或空操作按钮。
- 空间和成员写操作遵守独立幂等键、乐观锁、`GLOBAL` 不变量和最后一个活动 `MANAGER` 保护。
- 只实现 F4，不提前执行 F5 的 Playwright 最终验收、截图整理或最终联调补强。

## 开始状态

- 当前分支为 `main`，与 `origin/main` 一致，开始时工作区干净。
- 后端阶段 20、前端 F1～F3S 已完成；F4 范围补充已冻结并推送。
- 本阶段测试、真实联调、完整 Review、风险和未验证项将在完成实现后补充。

## 实际实现

- 新增用户治理页面，覆盖分页筛选、创建用户、角色与状态修改、一次性密码重置、解锁和撤销全部 Token。
- 新增知识空间及成员治理页面，覆盖空间创建、修改、启停，成员新增、恢复、改角色和撤销；所有空间与成员写操作携带独立幂等 Header 和当前版本。
- 新增托管知识页面，覆盖直接文本、标准浏览器 `FormData` 文件上传、详情、草稿修订、发布、归档和草稿删除。
- 新增案例审核、异步任务治理页面；本地评测页面只在 `VITE_ENABLE_EVALUATION=true` 构建时注册路由和导航。
- 管理员路由全部继续使用既有 `requiresAdmin` 守卫；菜单隐藏只改善体验，真实权限仍由后端裁决。
- 删除无真实业务能力的管理员占位页，补齐治理页面所需类型、API 适配和公共管理台样式。

## 契约修复

真实文件上传首次返回参数绑定错误。经用户确认后，仅修复既有后端上传契约的实现方式：`spaceId`、`title`、`idempotencyKey` 从 `@RequestPart` 改为 `@RequestParam`，文件本身仍使用 `@RequestPart MultipartFile`。字段名、路径、响应和业务语义均未变化，浏览器无需把普通字符串伪装成 JSON Part。

新增 `ManagedDocumentControllerWebTest`，使用 MockMvc 标准 multipart 字符串参数和文件验证 MVC 绑定、201 响应及应用用例入参。重新构建并启动后端后，使用真实管理员 Token 完成标准 `FormData` 上传，响应为 `DRAFT`，随后通过正式删除接口清理测试草稿。

## 真实联调结果

| 验证项 | 结果 | 说明 |
|---|---|---|
| ADMIN 用户治理 | 通过 | 创建、改角色、改状态、重置密码、解锁及撤销 Token 均走真实接口 |
| USER 越权 | 通过 | 普通用户调用平台管理员接口返回 403 |
| 空间生命周期 | 通过 | 创建、修改、停用、启用普通空间均成功；`GLOBAL` 停用返回 409 |
| 成员治理 | 通过 | 新增、恢复、改角色和撤销成功；陈旧版本及最后一位活动 `MANAGER` 操作返回 409 |
| 停用空间写保护 | 通过 | 停用后知识写入返回 409，重新启用后恢复正常 |
| 文本与文件草稿 | 通过 | 创建、修订和删除通过；标准浏览器 `FormData` 上传通过并已清理 |
| 列表查询 | 通过 | 已解决案例和异步任务分页连接真实后端成功 |
| 知识发布/归档 | 通过 | 用户授权后创建独立测试知识，真实 Embedding 索引任务成功；随后通过正式归档接口完成索引删除 |
| 案例发布 | 通过 | 受控 DRAFT 案例经正式发布接口和真实 Embedding 进入 `PUBLISHED`，验收后通过正式接口归档 |
| DEAD 任务重试 | 通过 | 为已归档测试文档建立限定的 `KNOWLEDGE_DELETE` DEAD 夹具；正式重试接口创建新任务，`retryOfTaskId` 正确且 Worker 幂等删除成功 |
| 本地评测 | 未执行 | F4 只要求完成可选入口与 API 适配，真实检索评测不是本阶段关闭门禁；默认构建继续隐藏该入口 |

联调产生的 `f4` 前缀普通空间和用户保留为禁用状态，便于后续 F5 复验；上传草稿已通过正式接口删除。在线关闭批次创建的测试知识和案例均已通过正式归档接口退出检索，普通测试空间恢复为 `DISABLED`。受控 DEAD 原任务和成功重试任务作为异步审计证据保留，不包含密钥、正文或模型输出。联调启动时发现根目录 `.env` 的 `SUPPORT_AGENT_MYSQL_PORT=13307`，但 `SUPPORT_AGENT_MYSQL_URL` 仍指向 3306；早期测试进程仅覆盖为 13307，未擅自修改用户本地配置。

## 测试结果

| 命令或验证 | 结果 | 证据 |
|---|---|---|
| `npm ci` | 通过 | 安装 377 个包，审计 0 个漏洞 |
| `npm run format:check` | 通过 | 全部前端文件符合 Prettier |
| `npm run lint` | 通过 | ESLint 0 warning |
| `npm run type-check` | 通过 | `vue-tsc --build --force` 成功 |
| `npm run test:unit` | 通过 | 16 个测试文件、49 项测试全部通过 |
| `npm run build` | 通过 | 默认关闭评测页面的生产构建成功 |
| `$env:VITE_ENABLE_EVALUATION='true'; npm run build` | 通过 | 评测页面和路由纳入构建后成功 |
| `npm audit --audit-level=high` | 通过 | 0 个漏洞 |
| `mvn validate` | 通过 | Maven 七项目 Reactor 校验成功 |
| `mvn test` | 通过 | 六个代码模块共 273 项测试全部通过 |
| `mvn verify -Pintegration` | 通过 | 基础设施集成 44 项、应用启动联调 9 项全部通过 |
| 标准 `FormData` 真实上传 | 通过 | 登录、创建 DRAFT、删除清理均成功 |
| 真实在线治理链路 | 通过 | 知识与案例发布/归档成功；受控 DEAD 任务经正式接口重试并成功执行 |

## 完整 Review

- Review 范围：全部已跟踪差异、未跟踪文件、删除文件、路由与角色边界、所有 F4 API 请求、幂等键、乐观锁版本、敏感信息、临时日志、禁用测试和文档状态。
- 发现并修复：浏览器 multipart 标量字段无法绑定；成员最后一位管理员的前端预判曾只统计当前页，已限制为成员仅一页时才预拦截，多页场景交由后端最终裁决；模板分页表达式已改为显式方法，消除构建错误。
- 复查结果：`git diff --check` 通过；无 `TODO/FIXME`、临时 `console`、跳过测试、Mock 业务页、密钥或密码进入版本管理。
- Review 结论：全部 F4 交付、真实联调、自动化测试和清理边界均无阻断问题；F4 可以标记为“已完成”。

## 文件范围

- `support-agent-web/src/api/`：新增用户、空间成员、知识、案例、异步任务、评测和幂等判断适配。
- `support-agent-web/src/types/`：新增 F4 公开契约类型。
- `support-agent-web/src/views/`：新增六组管理员治理页面。
- `support-agent-web/src/router/index.ts`、`src/layouts/MainLayout.vue`、`src/styles/base.css`：接入真实路由、导航和统一样式。
- `support-agent-web/tests/unit/admin-governance.api.spec.ts`：验证关键请求字段、版本和幂等键传递。
- `support-agent-interfaces/.../ManagedDocumentController.java` 及对应 Web 测试：修复标准 multipart 绑定并增加回归保护。

## 阶段结论

F4 规定的管理员治理能力、真实后端联调、在线索引写链路、受控任务恢复、自动化测试及完整 Review 均已通过。阶段在此关闭，不提前实现 F5；后续须由用户再次确认后执行 Playwright 端到端验收和质量补强。
