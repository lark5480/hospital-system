import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Router } from 'vue-router'
import { AUTH_ENABLED } from '@/config/auth'
import { useTabsStore } from '@/stores/tabs'
import * as authApi from '@/api/auth'

/** 认证持久化 key。刷新后凭此恢复登录态。 */
const STORAGE_KEY = 'hospital_auth'

/** 在 main.ts 中调用,把 router 实例注入 store。 */
let router: Router | null = null
export function bindRouter(r: Router) {
  router = r
}

export const useAuthStore = defineStore('auth', () => {
  const enabled = ref(AUTH_ENABLED)
  const ready = ref(false)
  const authenticated = ref(false)
  const username = ref('')
  const name = ref('')
  const department = ref<string | null>(null)
  const departmentId = ref<number | null>(null)
  const roles = ref<string[]>([])
  const authorities = ref<string[]>([])
  const token = ref<string | undefined>(undefined)

  /**
   * 初始化认证:
   *   - dev 模式: 自动以 doctor01 登录。
   *   - 生产模式: 不自动登录(让路由守卫跳转到 /login)。
   */
  async function init() {
    if (!enabled.value) {
      // dev 模式:优先走 localStorage 缓存的完整载荷恢复,避免重复调 API
      const raw = localStorage.getItem(STORAGE_KEY)
      if (raw) {
        try { restoreFromStorage(); ready.value = true; return }
        catch { /* fall through to auto login */ }
      }
      const devUser = localStorage.getItem('dev_username') || 'doctor01'
      await doLogin(devUser, devUser)
    } else {
      // 生产模式:从 localStorage 恢复登录态,实现刷新免登
      restoreFromStorage()
    }
    ready.value = true
  }

  /** 从 localStorage 还原认证状态。 */
  function restoreFromStorage() {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return
    try {
      const s = JSON.parse(raw)
      token.value = s.token
      username.value = s.username
      name.value = s.name ?? ''
      department.value = s.department ?? null
      departmentId.value = s.departmentId ?? null
      roles.value = s.roles ?? []
      authorities.value = s.authorities ?? []
      authenticated.value = true
    } catch {
      localStorage.removeItem(STORAGE_KEY)
    }
  }

  /** 把当前认证状态写入 localStorage,刷新后可恢复。 */
  function persist() {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        token: token.value,
        username: username.value,
        name: name.value,
        department: department.value,
        departmentId: departmentId.value,
        roles: roles.value,
        authorities: authorities.value
      })
    )
  }

  /** 清除持久化(退出时 + 401 兜底)。顺便清掉历史 dev 键。 */
  function clearPersisted() {
    localStorage.removeItem(STORAGE_KEY)
    localStorage.removeItem('dev_token')
    localStorage.removeItem('dev_username')
  }

  /** 登录。服务端返回完整认证信息(token + roles + authorities + 用户资料),并持久化到 localStorage。 */
  async function doLogin(loginPhone: string, loginPassword: string) {
    const res = await authApi.login(loginPhone, loginPassword)
    token.value = res.token
    username.value = res.username
    name.value = res.name
    department.value = res.department ?? null
    departmentId.value = res.departmentId ?? null
    roles.value = res.roles
    authorities.value = res.authorities
    authenticated.value = true
    persist()
  }

  /** 切换角色(dev 模式):重新以新 username 登录。 */
  async function switchRole(loginUsername: string) {
    authenticated.value = false
    await doLogin(loginUsername, loginUsername)
  }

  /**
   * 登录入口:dev 模式弹 prompt,生产模式跳转登录页。
   */
  function login() {
    if (!enabled.value) {
      const uname = prompt('登录用户名(dev 模式:doctor01/nurse01/pharmacist01/cashier01/admin01):', 'doctor01')
      if (uname) switchRole(uname)
    } else {
      router?.push('/login')
    }
  }

  function logout() {
    useTabsStore().reset()
    authenticated.value = false
    token.value = undefined
    roles.value = []
    authorities.value = []
    department.value = null
    departmentId.value = null
    clearPersisted()
    router?.push('/login')
  }

  function hasAuthority(authority: string): boolean {
    return authorities.value.includes(authority)
  }

  return {
    enabled, ready, authenticated, username, name, department, departmentId, roles, authorities, token,
    init, login, logout, hasAuthority, switchRole, doLogin
  }
})

/** 外部便捷引用 */
export function hasAuthority(authority: string): boolean {
  return useAuthStore().hasAuthority(authority)
}

/** 初始化认证(init 入口,main.ts 调用) */
export async function initAuth() {
  const store = useAuthStore()
  await store.init()
}
