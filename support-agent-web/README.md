# Support Agent Web

Support Agent 的独立 Vue 3 前端。工程不加入 Maven Reactor；开发时通过 Vite 代理访问本地 Spring Boot 后端。

## 环境

- Windows 11、PowerShell 7
- Node.js 24.x LTS
- npm 11.x
- 后端默认运行在 `http://localhost:8080`

## 本地启动

```powershell
Set-Location .\support-agent-web
Copy-Item .\.env.example .\.env.local
npm ci
npm run dev
```

浏览器访问 `http://localhost:5173`。Vite 会把 `/api/*` 代理到 `VITE_DEV_PROXY_TARGET`。`.env.local` 只允许配置非敏感前端参数；DashScope Key、密码和 Bearer Token 不得写入任何 `VITE_*` 变量。

## 质量命令

```powershell
npm run format:check
npm run lint
npm run type-check
npm run test:unit
npm run build
npm audit --audit-level=high
```

## Playwright 真实联调

首次执行先安装仓库锁定版本对应的 Chromium：

```powershell
Set-Location .\support-agent-web
npx playwright install chromium
```

测试账号只允许通过当前 PowerShell 进程注入，不得写入 `.env`、`.env.local` 或任何 `VITE_*` 变量：

```powershell
$env:E2E_BASE_URL = 'http://127.0.0.1:5173'
$env:E2E_ADMIN_USERNAME = '<专用本地管理员用户名>'
$env:E2E_ADMIN_PASSWORD = '<专用本地管理员密码>'
$env:E2E_USER_USERNAME = '<专用本地普通用户名>'
$env:E2E_USER_PASSWORD = '<专用本地普通用户一次性密码>'
npm run test:e2e
```

执行前必须确认 Docker 基础设施和后端 `http://localhost:8080` 已启动。测试会通过真实管理员接口创建或复位专用普通用户，覆盖强制改密、聊天、建议建单、手工工单、会话、记忆及管理员知识草稿治理；成功路径会关闭测试工单、删除测试会话和知识草稿，并在结束时恢复普通用户的一次性密码状态。

完整 E2E 包含真实 DashScope 聊天和 Embedding 调用，只有取得本次 API Key、网络和费用授权后才能执行。截图、HTML 报告及失败截图保存在被 Git 忽略的 `test-results/`、`playwright-report/`，不得提交或对外分享其中的本地测试数据。

## 当前能力

当前已完成 F1～F5：支持登录、刷新恢复、强制改密、账号安全、角色路由守卫、显式知识空间 AI 对话、会话重置、长期记忆，以及带空间归属的工单列表、详情、手工或 AI 建单和既有状态流转。

F4 管理员治理页面已经实现用户、知识空间和成员、托管知识、已解决案例及异步任务管理；设置 `VITE_ENABLE_EVALUATION=true` 时还会构建本地评测入口。知识与案例真实发布归档、受控 DEAD 任务人工重试、离线测试及基础设施集成门禁均已通过。F5 已补齐统一错误恢复、键盘焦点、窄屏保护和 Playwright 真实后端测试，包含经明确授权的 DashScope 在线问答验收。
