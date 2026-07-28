import { bindHttpRouter } from '@/api/http'
import { bindRouter, initAuth } from '@/stores/auth'
import '@/styles/index.css'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import { createPinia } from 'pinia'
import { createApp } from 'vue'
import App from './App.vue'
import router from './router'

// 先创建应用并挂载 Pinia / ElementPlus,再执行认证初始化——
// initAuth 内部会调用 useAuthStore(),必须在 Pinia 成为 active 实例之后,
// 否则会抛 "getActivePinia() was called but there was no active Pinia"。
const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(ElementPlus)

// 把 router 注入 auth store + http 拦截器,供 login/logout/401 兜底跳转使用
bindRouter(router)
bindHttpRouter(router)

// 全局错误处理器
app.config.errorHandler = (err, _instance, info) => {
  console.error('[Global Error]', err, info)
}

// 未处理的 Promise rejection
window.addEventListener('unhandledrejection', (event) => {
  console.error('[Unhandled Rejection]', event.reason)
})

// 认证初始化:从 localStorage 恢复登录态(刷新免登)。
// 完成后挂路由并 mount,确保路由守卫拿到的 auth 状态已就绪。
initAuth().finally(() => {
  app.use(router)
  app.mount('#app')
})
