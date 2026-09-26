import { beforeEach, describe, expect, it } from 'vitest'
import { clearAccessToken, readAccessToken, writeAccessToken } from '@/api/auth.session'

describe('认证会话存储', () => {
  beforeEach(() => sessionStorage.clear())

  it('只在当前标签页 sessionStorage 中保存和读取 Token', () => {
    writeAccessToken('opaque-token')

    expect(readAccessToken()).toBe('opaque-token')
    expect(localStorage.length).toBe(0)
  })

  it('可以清除当前 Token', () => {
    writeAccessToken('opaque-token')
    clearAccessToken()

    expect(readAccessToken()).toBeNull()
  })
})
