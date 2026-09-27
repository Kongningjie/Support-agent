import { afterEach, describe, expect, it, vi } from 'vitest'
import { listAllReadableKnowledgeSpaces } from '@/api/knowledge-space.api'
import { httpClient } from '@/api/http.client'
import type { ApiResult, PageResult } from '@/types/api.types'
import type { KnowledgeSpaceView } from '@/types/knowledge-space.types'

afterEach(() => vi.restoreAllMocks())

describe('knowledge space api', () => {
  it('按服务端分页读取全部可读活动空间且不传管理筛选', async () => {
    const global = space('00000000-0000-0000-0000-000000000001', 'GLOBAL', '企业公共空间')
    const erp = space('11111111-1111-1111-1111-111111111111', 'ERP', 'ERP 支持')
    const disabled = {
      ...erp,
      spaceId: '22222222-2222-2222-2222-222222222222',
      status: 'DISABLED' as const,
    }
    const get = vi
      .spyOn(httpClient, 'get')
      .mockResolvedValueOnce({ data: success(page([global], 1, 2)) })
      .mockResolvedValueOnce({ data: success(page([erp, disabled], 2, 2)) })

    await expect(listAllReadableKnowledgeSpaces()).resolves.toEqual([global, erp])
    expect(get).toHaveBeenNthCalledWith(1, '/knowledge-spaces', { params: { page: 1, size: 100 } })
    expect(get).toHaveBeenNthCalledWith(2, '/knowledge-spaces', { params: { page: 2, size: 100 } })
  })
})

/** 创建测试使用的可读活动空间。 */
function space(spaceId: string, code: string, name: string): KnowledgeSpaceView {
  return {
    spaceId,
    code,
    name,
    description: null,
    visibility: code === 'GLOBAL' ? 'ENTERPRISE' : 'RESTRICTED',
    status: 'ACTIVE',
    systemSpace: code === 'GLOBAL',
    version: 0,
    currentUserRole: code === 'GLOBAL' ? null : 'READER',
    createdAt: '2026-09-27T00:00:00Z',
    updatedAt: '2026-09-27T00:00:00Z',
  }
}

/** 创建两页空间测试中的单页结果。 */
function page(
  items: KnowledgeSpaceView[],
  current: number,
  totalPages: number,
): PageResult<KnowledgeSpaceView> {
  return { items, page: current, size: 100, totalElements: 2, totalPages }
}

/** 包装后端统一成功响应。 */
function success<T>(data: T): ApiResult<T> {
  return {
    code: 'SUCCESS',
    message: '成功',
    data,
    traceId: 'trace-space-test',
    timestamp: '2026-09-27T00:00:00Z',
  }
}
