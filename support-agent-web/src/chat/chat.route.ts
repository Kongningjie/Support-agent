/**
 * 判断路由会话是否需要从服务端重新加载。
 *
 * <p>发送消息后仅同步同一会话的 URL 时，保留 SSE 刚返回的工单建议等临时事件数据；
 * 只有真正切换到另一会话时才重新读取详情。</p>
 */
export function shouldLoadConversation(
  routeConversationId: string | null,
  currentConversationId: string | null,
): boolean {
  return routeConversationId !== null && routeConversationId !== currentConversationId
}
