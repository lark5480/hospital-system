import { useAuthStore } from '@/stores/auth'
import axios, { type AxiosError } from 'axios'
import { ElMessage } from 'element-plus'
import type { Router } from 'vue-router'

// 统一走 Gateway 的 /api 前缀;开发期由 vite.config.ts 的 proxy 转发到 :8104。
const http = axios.create({
  baseURL: '/api',
  timeout: 10000
})

/** 由 main.ts 注入,401 兜底跳转登录用。 */
let router: Router | null = null
export function bindHttpRouter(r: Router) {
  router = r
}

// 请求拦截器:带 JWT token(开发/生产统一)
http.interceptors.request.use((config) => {
  const token = useAuthStore().token
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截器 —— 401 时清除过期 token 并跳登录(恢复的旧 token 必然仍 401,不再 retry)
http.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    if (error.response) {
      const { status } = error.response

      // 401 时清除过期 token 并跳登录
      if (status === 401) {
        // R-56: 一次性清空"内存认证态 + 持久化缓存",统一由 store 管理底层 storage。
        // 原先只重置 token/authenticated/mustChangePassword,内存里的 roles/authorities/
        // 用户资料会残留,表现为"已跳回登录页但侧边栏仍按管理员权限渲染"。
        useAuthStore().resetAuthState()
        // 已经在登录页(典型:登录失败)时不再重复跳转,避免重复导航告警
        if (router?.currentRoute.value.path !== '/login') {
          router?.push('/login')
        }
      }

      // R-02/R-08/R-09: 鉴权收紧后,患者角色访问 B 端读接口会返回 403
      if (status === 403) {
        ElMessage.error('权限不足,无法执行该操作')
      }
    }
    return Promise.reject(error)
  }
)

export default http
