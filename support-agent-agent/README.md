# support-agent-agent

AgentScope、DashScope 与 Prompt 适配层，也是仓库中唯一允许直接依赖 AgentScope 的模块。

当前提供 Chat、意图识别、会话摘要、Embedding、Rerank、长期记忆候选和不可用降级适配器；Prompt 采用资源文件、变量白名单和版本哈希管理。所有不可信数据通过结构化数据区进入模型，模型输出仍需由应用层安全网关校验后才能外发或持久化。
