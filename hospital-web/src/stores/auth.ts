import * as authApi from '@/api/auth'
import { useTabsStore } from '@/stores/tabs'
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Router } from 'vue-router'

/** 认证持久化 key。刷新后凭此恢复登录态。 */
const STORAGE_KEY = 'hospital_auth'

// R-56: 由 localStorage 改为 sessionStorage,缩小 XSS 窃取与"关浏览器后令牌仍留存"的窗口;
// 彻底解决需改用 httpOnly Cookie + CSRF 防护(P3)。
//
// 注意:不能写成 `const STORAGE = sessionStorage`。隐私模式 / 站点数据被禁用时,
// 访问 sessionStorage 会抛 SecurityError —— 那是在模块 import 阶段执行,
// 会让整个应用直接白屏(连登录页都进不去)。这里改为初始化时探测一次,
// 不可用则退化为进程内 Map(功能可用,只是刷新后需要重新登录),与 stores/tabs.ts 的兜底思路一致。
const memoryFallback = new Map<string, string>()

const STORAGE: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> = (() => {
  try {
    const probe = '__auth_storage_probe__'
    sessionStorage.setItem(probe, '1')
    sessionStorage.removeItem(probe)
    return sessionStorage
  } catch {
    return {
      getItem: (key: string) => memoryFallback.get(key) ?? null,
      setItem: (key: string, value: string) => {
        memoryFallback.set(key, value)
      },
      removeItem: (key: string) => {
        memoryFallback.delete(key)
      }
    }
  }
})()

/** 旧版本用 localStorage 持久化认证态;升级后需清理,避免旧令牌仍被读取/恢复。 */
const LEGACY_STORAGE_KEYS = ['hospital_auth', 'dev_token', 'dev_username']

/** R-56: 清理旧 localStorage 中的认证数据(含历史 dev 键)。同样要防 Storage 不可用。 */
function clearLegacyLocalStorage() {
  try {
    for (const key of LEGACY_STORAGE_KEYS) {
      localStorage.removeItem(key)
    }
  } catch {
    // localStorage 不可用时无需清理(本来也没有旧值可读)
  }
}

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
   * 初始化认证:从 sessionStorage 恢复登录态(刷新免登);无缓存则保持未登录,由路由守卫跳 /login。
   */
  async function init() {
    restoreFromStorage()
    ready.value = true
  }

  /** R-56: 从 sessionStorage 还原认证状态。读取前先清掉旧 localStorage 残留。 */
  function restoreFromStorage() {
    clearLegacyLocalStorage()
    const raw = STORAGE.getItem(STORAGE_KEY)
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
      STORAGE.removeItem(STORAGE_KEY)
    }
  }

  /** R-56: 把当前认证状态写入 sessionStorage(刷新可恢复,关闭标签页即失效)。 */
  function persist() {
    STORAGE.setItem(
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

  /** 清除持久化(退出时 + 401 兜底)。同时清掉旧 localStorage 与历史 dev 键。 */
  function clearPersisted() {
    STORAGE.removeItem(STORAGE_KEY)
    clearLegacyLocalStorage()
  }

  /** 登录。服务端返回完整认证信息(token + roles + authorities + 用户资料),并持久化到 sessionStorage。 */
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

  /**
   * R-56: 统一清空"内存认证态 + 持久化缓存",供 logout 与 http.ts 的 401 兜底共用。
   *
   * <p>原先 401 分支只重置了 token / authenticated / mustChangePassword,
   * 内存里的 roles / authorities / 用户资料会残留 —— 表现为"令牌已失效跳回登录页,
   * 但侧边栏仍按管理员权限渲染",直到手动刷新才恢复,属权限展示不一致。
   * 这里一并清掉,顺带消除 logout 与该分支的重复逻辑。
   */
  function resetAuthState() {
    useTabsStore().reset()
    authenticated.value = false
    token.value = undefined
    username.value = ''
    name.value = ''
    roles.value = []
    authorities.value = []
    department.value = null
    departmentId.value = null
    mustChangePassword.value = false
    clearPersisted()
  }

  function logout() {
    resetAuthState()
    router?.push('/login')
  }

  function hasAuthority(authority: string): boolean {
    return authorities.value.includes(authority)
  }

  return {
    ready, authenticated, username, name, department, departmentId, roles, authorities, token,
    mustChangePassword,
    init, login, logout, hasAuthority, doLogin, setMustChangePassword,
    // R-56: 暴露给 http.ts 的 401 兜底使用,统一由 store 管理底层 storage,避免外部直连 localStorage
    clearPersisted,
    // R-56: 401 兜底需清空"内存态 + 缓存",用这个而不是只清缓存
    resetAuthState
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
