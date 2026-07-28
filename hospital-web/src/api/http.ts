import { useAuthStore } from '@/stores/auth'
import axios, { type AxiosError } from 'axios'
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
        const auth = useAuthStore()
        auth.token = undefined
        auth.authenticated = false
        localStorage.removeItem('hospital_auth')
        router?.push('/login')
      }
    }
    return Promise.reject(error)
  }
)

export default http
