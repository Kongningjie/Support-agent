import { afterEach, describe, expect, it, vi } from 'vitest'
import * as spaceApi from '@/api/knowledge-space-admin.api'
import * as knowledgeApi from '@/api/knowledge-admin.api'
import * as caseApi from '@/api/resolved-case.api'
import * as taskApi from '@/api/async-task.api'
import * as userApi from '@/api/user-admin.api'
import { hasUncertainOutcome } from '@/api/idempotency'
import { httpClient } from '@/api/http.client'
import { ApiError, type ApiResult } from '@/types/api.types'
import type { AsyncTaskView } from '@/types/async-task.types'
import type { KnowledgeSpaceView, SpaceMembershipView } from '@/types/knowledge-space.types'
import type { ManagedDocumentAction, ManagedDocumentDetails } from '@/types/knowledge.types'
import type { ResolvedCaseDetails } from '@/types/resolved-case.types'
import type { AdminUserView } from '@/types/user-admin.types'

afterEach(() => vi.restoreAllMocks())

describe('F4 管理员治理 API', () => {
  it('仅在没有 HTTP 响应的网络错误下要求复用原幂等键', () => {
    expect(hasUncertainOutcome(new ApiError('网络中断', null, 'NETWORK_ERROR', null, true))).toBe(
      true,
    )
    expect(
      hasUncertainOutcome(new ApiError('版本冲突', 409, 'VERSION_CONFLICT', null, false)),
    ).toBe(false)
    expect(hasUncertainOutcome(new Error('未知错误'))).toBe(false)
  })

  it('用户创建、角色和状态操作携带后端冻结字段', async () => {
    const user = sampleUser()
    const post = vi.spyOn(httpClient, 'post').mockResolvedValue({ data: success(user) })
    const patch = vi.spyOn(httpClient, 'patch').mockResolvedValue({ data: success(user) })

    await userApi.createUser({
      username: 'alice',
      displayName: 'Alice',
      password: 'temporary-password',
      role: 'USER',
      idempotencyKey: 'user-create-key',
    })
    await userApi.changeUserRole(user.userId, 'ADMIN', 3)
    await userApi.changeUserStatus(user.userId, 'DISABLED', 4)

    expect(post).toHaveBeenCalledWith('/admin/users', {
      username: 'alice',
      displayName: 'Alice',
      password: 'temporary-password',
      role: 'USER',
      idempotencyKey: 'user-create-key',
    })
    expect(patch).toHaveBeenNthCalledWith(1, `/admin/users/${user.userId}/role`, {
      role: 'ADMIN',
      version: 3,
    })
    expect(patch).toHaveBeenNthCalledWith(2, `/admin/users/${user.userId}/status`, {
      status: 'DISABLED',
      version: 4,
    })
  })

  it('空间写操作把调用方固定的幂等键放入 Header 并提交 expectedVersion', async () => {
    const space = sampleSpace()
    const post = vi.spyOn(httpClient, 'post').mockResolvedValue({ data: success(space) })
    const patch = vi.spyOn(httpClient, 'patch').mockResolvedValue({ data: success(space) })

    await spaceApi.createSpace(
      { code: 'OPS', name: '运维空间', description: '内部运维知识' },
      'same-space-key',
    )
    await spaceApi.updateSpace(
      space.spaceId,
      { name: '生产运维', description: null, visibility: 'ENTERPRISE', expectedVersion: 5 },
      'space-update-key',
    )
    await spaceApi.changeSpaceStatus(space.spaceId, 'disable', 6, 'space-disable-key')

    expect(post).toHaveBeenNthCalledWith(
      1,
      '/admin/knowledge-spaces',
      { code: 'OPS', name: '运维空间', description: '内部运维知识' },
      { headers: { 'Idempotency-Key': 'same-space-key' } },
    )
    expect(patch).toHaveBeenCalledWith(
      `/admin/knowledge-spaces/${space.spaceId}`,
      { name: '生产运维', description: null, visibility: 'ENTERPRISE', expectedVersion: 5 },
      { headers: { 'Idempotency-Key': 'space-update-key' } },
    )
    expect(post).toHaveBeenNthCalledWith(
      2,
      `/admin/knowledge-spaces/${space.spaceId}/disable`,
      { expectedVersion: 6 },
      { headers: { 'Idempotency-Key': 'space-disable-key' } },
    )
  })

  it('成员新增省略版本，修改和撤销提交当前版本与幂等 Header', async () => {
    const member = sampleMember()
    const put = vi.spyOn(httpClient, 'put').mockResolvedValue({ data: success(member) })
    const remove = vi.spyOn(httpClient, 'delete').mockResolvedValue({ data: success(member) })

    await spaceApi.putMember('space-id', member.userId, 'READER', null, 'member-new-key')
    await spaceApi.putMember('space-id', member.userId, 'MANAGER', 2, 'member-update-key')
    await spaceApi.revokeMember('space-id', member.userId, 3, 'member-revoke-key')

    expect(put).toHaveBeenNthCalledWith(
      1,
      `/knowledge-spaces/space-id/members/${member.userId}`,
      { role: 'READER' },
      { headers: { 'Idempotency-Key': 'member-new-key' } },
    )
    expect(put).toHaveBeenNthCalledWith(
      2,
      `/knowledge-spaces/space-id/members/${member.userId}`,
      { role: 'MANAGER', expectedVersion: 2 },
      { headers: { 'Idempotency-Key': 'member-update-key' } },
    )
    expect(remove).toHaveBeenCalledWith(`/knowledge-spaces/space-id/members/${member.userId}`, {
      data: { expectedVersion: 3 },
      headers: { 'Idempotency-Key': 'member-revoke-key' },
    })
  })

  it('知识发布归档、案例审核和 DEAD 任务重试不在 API 层改写调用方幂等键', async () => {
    const document = sampleDocument()
    const documentAction: ManagedDocumentAction = { document, taskId: '88' }
    const resolvedCase = sampleCase()
    const task = sampleTask()
    const post = vi
      .spyOn(httpClient, 'post')
      .mockResolvedValueOnce({ data: success(documentAction) })
      .mockResolvedValueOnce({ data: success(documentAction) })
      .mockResolvedValueOnce({ data: success(resolvedCase) })
      .mockResolvedValueOnce({ data: success(task) })

    await knowledgeApi.publishDocument(document.documentId, 2, 'document-publish-key')
    await knowledgeApi.archiveDocument(
      document.documentId,
      3,
      '已被新版替代',
      'document-archive-key',
    )
    await caseApi.actOnCase(resolvedCase.caseId, 'publish', {
      version: 4,
      idempotencyKey: 'case-publish-key',
    })
    await taskApi.retryTask(task.taskId, '依赖已恢复', 'task-retry-key')

    expect(post).toHaveBeenNthCalledWith(1, `/knowledge/documents/${document.documentId}/publish`, {
      version: 2,
      idempotencyKey: 'document-publish-key',
    })
    expect(post).toHaveBeenNthCalledWith(2, `/knowledge/documents/${document.documentId}/archive`, {
      version: 3,
      archiveReason: '已被新版替代',
      idempotencyKey: 'document-archive-key',
    })
    expect(post).toHaveBeenNthCalledWith(3, `/resolved-cases/${resolvedCase.caseId}/publish`, {
      version: 4,
      idempotencyKey: 'case-publish-key',
    })
    expect(post).toHaveBeenNthCalledWith(4, `/async-tasks/${task.taskId}/retry`, {
      reason: '依赖已恢复',
      idempotencyKey: 'task-retry-key',
    })
  })
})

/** 包装后端统一成功响应。 */
function success<T>(data: T): ApiResult<T> {
  return {
    code: 'SUCCESS',
    message: '成功',
    data,
    traceId: 'trace-f4',
    timestamp: '2026-09-28T00:00:00Z',
  }
}

/** 创建用户治理契约测试对象。 */
function sampleUser(): AdminUserView {
  return {
    userId: 'user-id',
    username: 'alice',
    displayName: 'Alice',
    role: 'USER',
    status: 'ACTIVE',
    version: 3,
    mustChangePassword: false,
    lockedUntil: null,
    createdAt: '2026-09-28T00:00:00Z',
    updatedAt: '2026-09-28T00:00:00Z',
  }
}

/** 创建空间治理契约测试对象。 */
function sampleSpace(): KnowledgeSpaceView {
  return {
    spaceId: 'space-id',
    code: 'OPS',
    name: '运维空间',
    description: null,
    visibility: 'RESTRICTED',
    status: 'ACTIVE',
    systemSpace: false,
    version: 5,
    currentUserRole: 'MANAGER',
    createdAt: '2026-09-28T00:00:00Z',
    updatedAt: '2026-09-28T00:00:00Z',
  }
}

/** 创建成员治理契约测试对象。 */
function sampleMember(): SpaceMembershipView {
  return {
    userId: 'member-id',
    username: 'member',
    displayName: 'Member',
    role: 'READER',
    status: 'ACTIVE',
    version: 2,
    createdAt: '2026-09-28T00:00:00Z',
    updatedAt: '2026-09-28T00:00:00Z',
    revokedAt: null,
  }
}

/** 创建知识治理契约测试对象。 */
function sampleDocument(): ManagedDocumentDetails {
  return {
    documentId: '10',
    spaceId: 'space-id',
    space: { spaceId: 'space-id', code: 'OPS', name: '运维空间' },
    title: '排障知识',
    inputType: 'DIRECT_TEXT',
    status: 'DRAFT',
    version: 2,
    originalFileName: null,
    mediaType: 'text/plain',
    rawContent: '正文',
    contentHash: 'hash',
    indexFailureReason: null,
    archiveReason: null,
    createdAt: '2026-09-28T00:00:00Z',
    updatedAt: '2026-09-28T00:00:00Z',
    publishedAt: null,
    archivedAt: null,
  }
}

/** 创建案例治理契约测试对象。 */
function sampleCase(): ResolvedCaseDetails {
  return {
    caseId: '20',
    spaceId: 'space-id',
    space: { spaceId: 'space-id', code: 'OPS', name: '运维空间' },
    sourceTicketNo: 'T000000000001',
    sourceTicketTitle: '故障',
    title: '案例',
    problem: '问题',
    cause: '根因',
    solution: '方案',
    status: 'DRAFT',
    version: 4,
    publishFailureReason: null,
    rejectionReason: null,
    archiveReason: null,
    createdAt: '2026-09-28T00:00:00Z',
    updatedAt: '2026-09-28T00:00:00Z',
    publishedAt: null,
    archivedAt: null,
  }
}

/** 创建任务治理契约测试对象。 */
function sampleTask(): AsyncTaskView {
  return {
    taskId: '30',
    taskType: 'KNOWLEDGE_INDEX',
    aggregateType: 'MANAGED_DOCUMENT',
    aggregateId: '10',
    aggregateVersion: 2,
    status: 'DEAD',
    attemptCount: 3,
    maxAttempts: 3,
    nextRunAt: '2026-09-28T00:00:00Z',
    lastErrorCode: 'TIMEOUT',
    lastErrorMessage: '依赖超时',
    retryOfTaskId: null,
    manualRetryReason: null,
    createdAt: '2026-09-28T00:00:00Z',
    startedAt: null,
    finishedAt: '2026-09-28T00:00:00Z',
    updatedAt: '2026-09-28T00:00:00Z',
  }
}
