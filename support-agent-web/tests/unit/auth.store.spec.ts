import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import * as authApi from '@/api/auth.api'
import { readAccessToken, writeAccessToken } from '@/api/auth.session'
import { useAuthStore } from '@/stores/auth.store'
import type { CurrentUser, LoginResponse } from '@/types/auth.types'

vi.mock('@/api/auth.api')

const user: CurrentUser = {
  userId: '7e18c64e-8a09-4efd-a343-d40a00ce4915',
  username: 'alice',
  displayName: 'Alice',
  role: 'USER',
  status: 'ACTIVE',
  version: 0,
  mustChangePassword: false,
  lockedUntil: null,
  createdAt: '2026-09-26T00:00:00Z',
  updatedAt: '2026-09-26T00:00:00Z',
}

describe('认证 Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetAllMocks()
    sessionStorage.clear()
  })

  it('登录成功后保存 Token 和用户摘要', async () => {
    vi.mocked(authApi.login).mockResolvedValue({
      accessToken: 'opaque-token',
      tokenType: 'Bearer',
      expiresAt: '2026-09-26T02:00:00Z',
      user,
    } satisfies LoginResponse)
    const store = useAuthStore()

    await store.signIn({ username: 'alice', password: 'a-secure-password' })

    expect(readAccessToken()).toBe('opaque-token')
    expect(store.user).toEqual(user)
    expect(store.isAuthenticated).toBe(true)
  })

  it('刷新恢复时使用已有 Token 查询本人信息', async () => {
    writeAccessToken('existing-token')
    vi.mocked(authApi.getCurrentUser).mockResolvedValue(user)
    const store = useAuthStore()

    await store.restore()

    expect(authApi.getCurrentUser).toHaveBeenCalledOnce()
    expect(store.user).toEqual(user)
    expect(store.initialized).toBe(true)
  })

  it('恢复失败时清除无效 Token', async () => {
    writeAccessToken('expired-token')
    vi.mocked(authApi.getCurrentUser).mockRejectedValue(new Error('expired'))
    const store = useAuthStore()

    await expect(store.restore()).rejects.toThrow('expired')

    expect(readAccessToken()).toBeNull()
    expect(store.user).toBeNull()
    expect(store.initialized).toBe(true)
  })

  it('改密成功后清除后端已撤销的旧会话', async () => {
    vi.mocked(authApi.login).mockResolvedValue({
      accessToken: 'opaque-token',
      tokenType: 'Bearer',
      expiresAt: '2026-09-26T02:00:00Z',
      user,
    })
    vi.mocked(authApi.changePassword).mockResolvedValue({ ...user, version: 1 })
    const store = useAuthStore()
    await store.signIn({ username: 'alice', password: 'a-secure-password' })

    await store.updatePassword({
      oldPassword: 'a-secure-password',
      newPassword: 'a-new-secure-password',
    })

    expect(readAccessToken()).toBeNull()
    expect(store.user).toBeNull()
  })

  it('注销请求失败也清除本地会话', async () => {
    vi.mocked(authApi.login).mockResolvedValue({
      accessToken: 'opaque-token',
      tokenType: 'Bearer',
      expiresAt: '2026-09-26T02:00:00Z',
      user,
    })
    vi.mocked(authApi.logout).mockRejectedValue(new Error('network'))
    const store = useAuthStore()
    await store.signIn({ username: 'alice', password: 'a-secure-password' })

    await expect(store.signOut()).rejects.toThrow('network')

    expect(readAccessToken()).toBeNull()
    expect(store.user).toBeNull()
  })
})
