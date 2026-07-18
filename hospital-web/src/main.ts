import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import { initAuth, bindRouter } from '@/stores/auth'
import { bindHttpRouter } from '@/api/http'

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

// 认证初始化:本地开发态立即 resolve;AUTH_ENABLED 态走真登录。
// 完成后挂路由并 mount,确保路由守卫拿到的 auth 状态已就绪。
initAuth().finally(() => {
  app.use(router)
  app.mount('#app')
})
