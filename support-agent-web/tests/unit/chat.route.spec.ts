import { describe, expect, it } from 'vitest'
import { shouldLoadConversation } from '@/chat/chat.route'

describe('chat route synchronization', () => {
  it('仅同步当前会话 URL 时保留流式响应携带的临时数据', () => {
    expect(shouldLoadConversation('conversation-1', 'conversation-1')).toBe(false)
  })

  it('切换到其他会话时重新读取服务端详情', () => {
    expect(shouldLoadConversation('conversation-2', 'conversation-1')).toBe(true)
  })

  it('新会话入口不触发历史会话读取', () => {
    expect(shouldLoadConversation(null, 'conversation-1')).toBe(false)
  })
})
