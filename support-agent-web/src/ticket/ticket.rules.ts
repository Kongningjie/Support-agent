import type { TicketStatus } from '@/types/ticket.types'

/** 返回稳定状态的中文名称，提交值仍保持英文枚举。 */
export function ticketStatusLabel(status: TicketStatus): string {
  return { DRAFT: '草稿', OPEN: '待处理', RESOLVED: '已解决', CLOSED: '已关闭' }[status]
}

/** 判断当前状态是否允许修改人工输入的草稿字段。 */
export function canReviseTicket(status: TicketStatus): boolean {
  return status === 'DRAFT'
}

/** 判断当前状态是否允许提交为开放工单。 */
export function canSubmitTicket(status: TicketStatus): boolean {
  return status === 'DRAFT'
}

/** 判断当前状态是否允许填写根因和方案并解决。 */
export function canResolveTicket(status: TicketStatus): boolean {
  return status === 'OPEN'
}

/** 判断当前状态是否允许人工关闭。 */
export function canCloseTicket(status: TicketStatus): boolean {
  return status === 'DRAFT' || status === 'OPEN'
}
