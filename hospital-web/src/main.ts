import { bindHttpRouter } from '@/api/http'
import { bindRouter, initAuth } from '@/stores/auth'
import '@/styles/index.css'
// R-39: Element Plus 组件已改为按需自动引入(见 vite.config.ts 的 unplugin-vue-components),
// 故不再 `import ElementPlus` 与 `app.use(ElementPlus)` 全量注册。
// 但样式仍保留全量引入 —— styles/element.css 的 --el-* 主题映射层要求它在 index.css 之后加载,
// 且 ElMessage / ElMessageBox 这类"显式 import 的函数式 API"不走 resolver,只有全量 CSS 才能保证有样式。
import 'element-plus/dist/index.css'
// R-39: 必须显式注册 loading 指令。
// unplugin-vue-components 只解析模板里的 <el-xxx> 标签,**不会**解析 v-loading 这类指令
// (实测构建产物里完全没有 loading 组件代码,20 处 v-loading 会静默失效、控制台报
// "Failed to resolve directive: loading")。这里用 ElLoading 插件单独引入,
// 只带进 loading 一个组件,不影响整体按需引入的收益。
import { ElLoading } from 'element-plus'
import { createPinia } from 'pinia'
import { createApp } from 'vue'
import App from './App.vue'
import router from './router'

// 先创建应用并挂载 Pinia,再执行认证初始化——
// initAuth 内部会调用 useAuthStore(),必须在 Pinia 成为 active 实例之后,
// 否则会抛 "getActivePinia() was called but there was no active Pinia"。
const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
// R-39: 注册 v-loading 指令(全仓 20 处使用)
app.use(ElLoading)

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

// 认证初始化:从 sessionStorage 恢复登录态(刷新免登)。
// 完成后挂路由并 mount,确保路由守卫拿到的 auth 状态已就绪。
initAuth().finally(() => {
  app.use(router)
  app.mount('#app')
})
