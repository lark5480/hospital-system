import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'

const STORAGE_KEY = 'hospital_auth'

describe('auth store · 权限判断 hasAuthority', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    sessionStorage.clear()
    localStorage.clear()
  })

  it('拥有权限时返回 true', () => {
    const auth = useAuthStore()
    auth.authorities = ['visit:entry', 'charge:pay']
    expect(auth.hasAuthority('visit:entry')).toBe(true)
    expect(auth.hasAuthority('charge:pay')).toBe(true)
  })

  it('缺少权限时返回 false', () => {
    const auth = useAuthStore()
    auth.authorities = ['visit:entry']
    expect(auth.hasAuthority('system:admin')).toBe(false)
  })

  it('未登录(无任何权限)时返回 false', () => {
    const auth = useAuthStore()
    expect(auth.authorities).toEqual([])
    expect(auth.hasAuthority('visit:entry')).toBe(false)
  })
})

describe('auth store · R-56 token 存储窗口收窄', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    sessionStorage.clear()
    localStorage.clear()
  })

  it('init() 从 sessionStorage 恢复登录态', async () => {
    sessionStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        token: 'tok-session',
        username: '13800000000',
        name: '张三',
        department: '内科',
        departmentId: 1,
        roles: ['DOCTOR'],
        authorities: ['visit:entry'],
        mustChangePassword: true
      })
    )

    const auth = useAuthStore()
    await auth.init()

    expect(auth.authenticated).toBe(true)
    expect(auth.token).toBe('tok-session')
    expect(auth.hasAuthority('visit:entry')).toBe(true)
    expect(auth.mustChangePassword).toBe(true)
  })

  it('init() 会清理 localStorage 中的旧令牌,旧令牌不再生效', async () => {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ token: 'tok-legacy', authorities: ['system:admin'] })
    )

    const auth = useAuthStore()
    await auth.init()

    // 旧 localStorage 值被清除,且不会被恢复成登录态
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull()
    expect(auth.authenticated).toBe(false)
    expect(auth.token).toBeUndefined()
    expect(auth.hasAuthority('system:admin')).toBe(false)
  })
})
