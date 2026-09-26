# Support Agent 前端文档

本目录保存前端冻结实施方案、前端工程规范和各阶段实际工作记录。前端源代码位于仓库根目录 `support-agent-web/`，当前已完成阶段 F1；判断实际能力时必须结合最近阶段记录，不得把后续阶段计划写成已完成功能。

## 文档结构

```text
docs/frontend/
├─ implementation-plan/
│  ├─ 00-frontend-phased-implementation-plan.md
│  └─ 01-frontend-engineering-standard.md
└─ work-logs/
   ├─ README.md
   ├─ TEMPLATE.md
   └─ YYYY-MM-DD-<stage-name>.md
```

## 当前状态

| 项目 | 状态 | 含义 |
|---|---|---|
| 前端方案 | 已冻结 | 技术栈、页面范围、阶段、联调方式和质量门禁已经确认 |
| 阶段 F1 | 已完成 | 工程基础、认证、会话恢复、强制改密、角色守卫和应用外壳已通过门禁 |
| 阶段 F2～F5 | 待执行 | 聊天、会话、记忆、工单、管理员治理和最终 E2E 尚未实现 |
| 本地联调 | F1 已完成 | F1 认证场景已连接真实本地后端；完整业务联调仍属于 F2～F5 |

## 执行入口

开始任一前端阶段前必须完整阅读：

1. [前端分阶段冻结实施计划](implementation-plan/00-frontend-phased-implementation-plan.md)。
2. [前端工程与 AI Coding 强制规范](implementation-plan/01-frontend-engineering-standard.md)。
3. 后端的 [当前系统基线](../implementation-plan/10-current-system-baseline.md) 和 [API 契约](../implementation-plan/04-api-and-sse.md)。
4. 最近一份 [前端阶段工作记录](work-logs/README.md)。

一次只执行一个前端阶段。阶段测试、完整 Review 和工作记录未完成前，不得进入下一阶段，也不得自动提交或推送。
