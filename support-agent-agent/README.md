# support-agent-agent

取消观测记录当前请求的上游取消请求、本地释放和释放失败次数；真实本地 HTTP 集成测试同时验证等待退出与对端连接关闭，不将本地释放解释为供应商停止计费。

AgentScope、DashScope 与 Prompt 适配层，也是仓库中唯一允许直接依赖 AgentScope 的模块。

当前提供 Chat、意图识别、会话摘要、Embedding、Rerank、长期记忆候选和不可用降级适配器；Prompt 采用资源文件、变量白名单和版本哈希管理。所有不可信数据通过结构化数据区进入模型，模型输出仍需由应用层安全网关校验后才能外发或持久化。

Chat/Intent 显式使用可取消的 JDK HTTP 传输适配，不更换 AgentScope SDK 或全局默认传输。取消只释放当前请求的订阅和响应资源，不关闭共享客户端；工单 Agent 同时在后续推理及工具入口检查取消。Judge、摘要、Embedding/Rerank 等独立能力不因本批次增加 SSE 取消语义。真实供应商停止推理及计费未验证。
