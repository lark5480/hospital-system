import * as authApi from '@/api/auth'
import { useTabsStore } from '@/stores/tabs'
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Router } from 'vue-router'

/** 认证持久化 key。刷新后凭此恢复登录态。 */
const STORAGE_KEY = 'hospital_auth'

/** 在 main.ts 中调用,把 router 实例注入 store。 */
let router: Router | null = null
export function bindRouter(r: Router) {
  router = r
}

export const useAuthStore = defineStore('auth', () => {
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
   * R-10: 仍在使用系统默认口令(123456)。
   * 为 true 时路由守卫会把用户挡在改密页,直到改密成功置回 false。
   */
  const mustChangePassword = ref(false)

  /**
   * 初始化认证:从 localStorage 恢复登录态(刷新免登);无缓存则保持未登录,由路由守卫跳 /login。
   */
  async function init() {
    restoreFromStorage()
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
      // 旧版本持久化的缓存没有该字段,按 false 处理(刷新后仍会由下次登录重新判定)
      mustChangePassword.value = s.mustChangePassword === true
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
        authorities: authorities.value,
        mustChangePassword: mustChangePassword.value
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
    // R-10: 后端判定该账号仍在用默认口令 → 前端强制改密
    mustChangePassword.value = res.mustChangePassword === true
    authenticated.value = true
    persist()
  }

  /**
   * R-10: 改密成功后调用,解除"强制改密"拦截。
   * 只改内存中的标记不足以跨刷新,故同步更新持久化缓存。
   */
  function setMustChangePassword(value: boolean) {
    mustChangePassword.value = value
    if (authenticated.value) {
      persist()
    }
  }

  /** 登录入口:跳转登录页(手机号 + 密码)。 */
  function login() {
    router?.push('/login')
  }

  function logout() {
    useTabsStore().reset()
    authenticated.value = false
    token.value = undefined
    roles.value = []
    authorities.value = []
    department.value = null
    departmentId.value = null
    mustChangePassword.value = false
    clearPersisted()
    router?.push('/login')
  }

  function hasAuthority(authority: string): boolean {
    return authorities.value.includes(authority)
  }

  return {
    ready, authenticated, username, name, department, departmentId, roles, authorities, token,
    mustChangePassword,
    init, login, logout, hasAuthority, doLogin, setMustChangePassword
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
