import { createRouter, createWebHistory, type RouteLocationNormalized } from 'vue-router'
import { useAuthStore } from '@/stores/auth.store'

declare module 'vue-router' {
  interface RouteMeta {
    requiresAuth?: boolean
    anonymousOnly?: boolean
    requiresAdmin?: boolean
    allowsPasswordChange?: boolean
  }
}

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      redirect: '/chat',
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/auth/LoginView.vue'),
      meta: { anonymousOnly: true },
    },
    {
      path: '/change-password',
      name: 'change-password',
      component: () => import('@/views/auth/ChangePasswordView.vue'),
      meta: { requiresAuth: true, allowsPasswordChange: true },
    },
    {
      path: '/chat',
      name: 'chat',
      component: () => import('@/views/chat/ChatView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/conversations',
      name: 'conversations',
      component: () => import('@/views/conversation/ConversationListView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/conversations/:id',
      name: 'conversation-details',
      component: () => import('@/views/chat/ChatView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/memories',
      name: 'memories',
      component: () => import('@/views/memory/MemoryView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/account',
      name: 'account',
      component: () => import('@/views/auth/AccountView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/admin/users',
      name: 'admin-users',
      component: () => import('@/views/shell/AdminAccessView.vue'),
      meta: { requiresAuth: true, requiresAdmin: true },
    },
    {
      path: '/forbidden',
      name: 'forbidden',
      component: () => import('@/views/system/ForbiddenView.vue'),
      meta: { requiresAuth: true, allowsPasswordChange: true },
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/system/NotFoundView.vue'),
    },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.initialized) {
    try {
      await auth.restore()
    } catch {
      // 恢复失败后的跳转由下方统一认证分支决定，不暴露原始异常。
    }
  }
  return resolveNavigation(to, auth)
})

/**
 * 根据认证、强制改密和管理员身份计算守卫结果。
 *
 * @param to 即将进入的目标路由
 * @param auth 当前认证 Store 的只读守卫所需状态
 * @returns 允许时返回 true，否则返回安全目标路由
 */
export function resolveNavigation(
  to: RouteLocationNormalized,
  auth: ReturnType<typeof useAuthStore>,
): true | { name: string; query?: Record<string, string> } {
  if (to.meta.requiresAuth && !auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.anonymousOnly && auth.isAuthenticated) {
    return { name: auth.mustChangePassword ? 'change-password' : 'chat' }
  }
  if (auth.isAuthenticated && auth.mustChangePassword && !to.meta.allowsPasswordChange) {
    return { name: 'change-password' }
  }
  if (to.meta.requiresAdmin && !auth.isAdmin) {
    return { name: 'forbidden' }
  }
  return true
}
