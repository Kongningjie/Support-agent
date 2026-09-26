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

当前 F1 范围包括登录、刷新恢复、强制改密、账号安全、角色路由守卫和应用外壳。聊天、工单和治理业务由后续阶段实现。
