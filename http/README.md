# IDEA HTTP Client 目录

业务接口按阶段实现后，在此目录加入健康检查、知识、聊天、工单、案例、异步任务和检索评估请求样例。

当前提供健康检查、托管知识、聊天、会话生命周期、工单、案例、异步任务和检索评测请求；样例不包含真实凭据。

按 `00` 到 `60` 的编号顺序验证完整闭环。`25-conversations.http` 中的 `accessToken` 必须替换为登录接口返回的临时 Bearer Token。案例生成和知识发布由异步 Worker 完成，调用后应通过
`50-async-task.http` 查询任务状态。`60-retrieval-evaluation.http` 只适用于 `dev/test` Profile。
