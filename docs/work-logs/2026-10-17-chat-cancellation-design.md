# 聊天断连取消方案记录

日期：2026-10-17。状态：仅完成设计文档，未进入实现。

## 用户确认与变更

用户确认选择断连取消，不做 SSE 断点续传，并要求将讨论方案写入 Markdown。

新增 docs/design-freezes/08-chat-disconnect-cancellation.md，记录请求级取消、模型订阅和上游请求释放、重生成阻断、运行清理、提交竞争、幂等重试与结果重放，以及验收边界。同步冻结方案和实施文档索引，不修改已完成历史工作记录或当前能力基线。

## Review 与验证

核对现有 ChatController、ChatUseCase、ChatModelPort、DashScopeChatModelAdapter 和 RedisConversationStoreAdapter。当前只具备发送失败清理，尚无完整取消传播；Redis begin 先校验版本的顺序可能阻碍旧版本客户端重放，已列为后续修正项。

文档 Review 范围包含取消原因含义、请求隔离、提交及清理竞争、权限和代际约束、重放副作用、首次会话 ID 丢失限制、SDK 传输层取消验证。没有把计划能力或供应商停止计费写成已验证事实。

仅执行文档差异和链接静态检查；不运行应用测试，不调用真实模型，不修改业务代码。后续须单独确认实施计划并开始执行；本次不提交或推送。
