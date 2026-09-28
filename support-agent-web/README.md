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
npm run lint
npm run type-check
npm run test:unit
npm run build
```

当前已完成 F1～F3S：支持登录、刷新恢复、强制改密、账号安全、角色路由守卫、显式知识空间 AI 对话、会话重置、长期记忆，以及带空间归属的工单列表、详情、手工或 AI 建单和既有状态流转。

F4 管理员治理页面已经实现用户、知识空间和成员、托管知识、已解决案例及异步任务管理；设置 `VITE_ENABLE_EVALUATION=true` 时还会构建本地评测入口。F4 的离线门禁和非模型真实联调已通过，真实知识/案例发布归档及受控 DEAD 任务重试仍须获得在线模型授权并完成验收，因此当前不得把 F4 标记为已完成。
