import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { RouteLocationNormalized } from 'vue-router'
import { writeAccessToken } from '@/api/auth.session'
import { resolveNavigation } from '@/router'
import { useAuthStore } from '@/stores/auth.store'
import type { CurrentUser } from '@/types/auth.types'

const baseUser: CurrentUser = {
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

/** 创建守卫测试所需的最小规范化路由。 */
function route(
  meta: RouteLocationNormalized['meta'],
  fullPath = '/target',
): RouteLocationNormalized {
  return {
    fullPath,
    hash: '',
    matched: [],
    meta,
    name: 'target',
    params: {},
    path: fullPath,
    query: {},
    redirectedFrom: undefined,
  }
}

describe('认证路由守卫', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    sessionStorage.clear()
  })

  it('匿名访问受保护页面时携带安全回跳地址进入登录页', () => {
    const result = resolveNavigation(route({ requiresAuth: true }, '/account'), useAuthStore())

    expect(result).toEqual({ name: 'login', query: { redirect: '/account' } })
  })

  it('强制改密用户不能进入普通业务页面', () => {
    const store = useAuthStore()
    store.user = { ...baseUser, mustChangePassword: true }
    writeAccessToken('restricted-token')

    expect(resolveNavigation(route({ requiresAuth: true }), store)).toEqual({
      name: 'change-password',
    })
  })

  it('普通用户进入管理员页面时转到无权限页', () => {
    const store = useAuthStore()
    store.user = baseUser
    writeAccessToken('user-token')

    expect(resolveNavigation(route({ requiresAuth: true, requiresAdmin: true }), store)).toEqual({
      name: 'forbidden',
    })
  })

  it('管理员可以进入管理员页面', () => {
    const store = useAuthStore()
    store.user = { ...baseUser, role: 'ADMIN' }
    writeAccessToken('admin-token')

    expect(resolveNavigation(route({ requiresAuth: true, requiresAdmin: true }), store)).toBe(true)
  })
})
