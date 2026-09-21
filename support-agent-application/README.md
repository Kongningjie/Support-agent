# support-agent-application

应用用例与端口层，负责事务边界之外的业务编排，并向外部能力声明稳定接口。

当前包含聊天与 SSE 事件编排、混合检索、工单和案例闭环、知识发布、异步任务、认证与资源归属、会话生命周期、滚动摘要、长期记忆、安全策略、运行指标和评测服务。具体 MySQL、Redis、Elasticsearch、DashScope 与 Web 类型不得泄漏到端口契约中。
