# support-agent-interfaces

REST、SSE、Spring Security、DTO、异常映射和 OpenAPI 接口层。

当前提供认证与本人账号、管理员用户治理、知识空间发现与成员治理、聊天和会话、长期记忆、工单、知识、案例、异步任务与开发/测试评测接口。新会话、手工工单和知识草稿要求显式 `spaceId`，会话重置省略该字段时保留原空间；普通 JSON 响应使用 `ApiResult<T>`，分页使用 `PageResult<T>`；聊天使用独立 SSE 协议，模型原始 delta 不直接透传。
