import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import * as authApi from '@/api/auth.api'
import { clearAccessToken, readAccessToken, writeAccessToken } from '@/api/auth.session'
import type { ChangePasswordRequest, CurrentUser, LoginRequest } from '@/types/auth.types'

/** 当前标签页内认证会话的唯一跨页面状态。 */
export const useAuthStore = defineStore('auth', () => {
  const user = ref<CurrentUser | null>(null)
  const initialized = ref(false)
  const pending = ref(false)

  const isAuthenticated = computed(() => Boolean(user.value && readAccessToken()))
  const isAdmin = computed(() => user.value?.role === 'ADMIN')
  const mustChangePassword = computed(() => user.value?.mustChangePassword === true)

  /**
   * 校验登录信息并建立当前标签页认证会话。
   *
   * @param request 用户名和本次登录明文密码
   */
  async function signIn(request: LoginRequest): Promise<void> {
    pending.value = true
    try {
      const result = await authApi.login(request)
      writeAccessToken(result.accessToken)
      user.value = result.user
      initialized.value = true
    } finally {
      pending.value = false
    }
  }

  /**
   * 使用 sessionStorage 中的 Token 恢复用户摘要。
   * 无 Token 时只完成初始化；校验失败由 HTTP 层清理 Token。
   */
  async function restore(): Promise<void> {
    if (initialized.value) {
      return
    }
    const token = readAccessToken()
    if (!token) {
      initialized.value = true
      return
    }
    pending.value = true
    try {
      user.value = await authApi.getCurrentUser()
    } catch (error) {
      clearSession()
      throw error
    } finally {
      pending.value = false
      initialized.value = true
    }
  }

  /** 撤销当前 Token；无论网络结果如何都清除本地会话。 */
  async function signOut(): Promise<void> {
    pending.value = true
    try {
      if (readAccessToken()) {
        await authApi.logout()
      }
    } finally {
      clearSession()
      pending.value = false
    }
  }

  /**
   * 修改本人密码；成功后按后端契约清除已经失效的全部旧 Token。
   *
   * @param request 当前密码和符合规则的新密码
   */
  async function updatePassword(request: ChangePasswordRequest): Promise<void> {
    pending.value = true
    try {
      await authApi.changePassword(request)
      clearSession()
    } finally {
      pending.value = false
    }
  }

  /** 撤销本人全部 Token；成功后清除当前标签页会话。 */
  async function revokeEveryToken(): Promise<void> {
    pending.value = true
    try {
      await authApi.revokeAllTokens()
      clearSession()
    } finally {
      pending.value = false
    }
  }

  /** 清除内存和 sessionStorage 中的认证状态。 */
  function clearSession(): void {
    clearAccessToken()
    user.value = null
  }

  return {
    user,
    initialized,
    pending,
    isAuthenticated,
    isAdmin,
    mustChangePassword,
    signIn,
    restore,
    signOut,
    updatePassword,
    revokeEveryToken,
    clearSession,
  }
})
