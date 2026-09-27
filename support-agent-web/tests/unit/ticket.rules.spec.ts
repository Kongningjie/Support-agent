import { describe, expect, it } from 'vitest'
import {
  canCloseTicket,
  canResolveTicket,
  canReviseTicket,
  canSubmitTicket,
  ticketStatusLabel,
} from '@/ticket/ticket.rules'
import type { TicketStatus } from '@/types/ticket.types'

describe('ticket rules', () => {
  it.each<[TicketStatus, string]>([
    ['DRAFT', '草稿'],
    ['OPEN', '待处理'],
    ['RESOLVED', '已解决'],
    ['CLOSED', '已关闭'],
  ])('为 %s 返回稳定中文名称', (status, label) => {
    expect(ticketStatusLabel(status)).toBe(label)
  })

  it('只为后端状态机允许的动作开放入口', () => {
    expect(canReviseTicket('DRAFT')).toBe(true)
    expect(canSubmitTicket('DRAFT')).toBe(true)
    expect(canResolveTicket('DRAFT')).toBe(false)
    expect(canCloseTicket('DRAFT')).toBe(true)

    expect(canReviseTicket('OPEN')).toBe(false)
    expect(canSubmitTicket('OPEN')).toBe(false)
    expect(canResolveTicket('OPEN')).toBe(true)
    expect(canCloseTicket('OPEN')).toBe(true)

    expect(canResolveTicket('RESOLVED')).toBe(false)
    expect(canCloseTicket('RESOLVED')).toBe(false)
    expect(canResolveTicket('CLOSED')).toBe(false)
    expect(canCloseTicket('CLOSED')).toBe(false)
  })
})
