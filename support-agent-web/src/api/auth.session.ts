const TOKEN_KEY = 'support-agent.access-token'

/** 从当前浏览器标签页读取不透明访问 Token。 */
export function readAccessToken(): string | null {
  return sessionStorage.getItem(TOKEN_KEY)
}

/**
 * 将登录成功返回的原始 Token 保存到当前标签页。
 *
 * @param token 后端仅返回一次的不透明访问 Token
 */
export function writeAccessToken(token: string): void {
  sessionStorage.setItem(TOKEN_KEY, token)
}

/** 清除当前标签页的访问 Token。 */
export function clearAccessToken(): void {
  sessionStorage.removeItem(TOKEN_KEY)
}
