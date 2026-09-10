import { defineConfig, mergeConfig } from 'vitest/config'
import viteConfig from './vite.config'

// R-61: 前端单元测试配置。
//
// R-39 补充:这里**复用 vite.config.ts 的全部插件**(mergeConfig),
// 而不是只挂 @vitejs/plugin-vue —— 因为 element-plus 的按需引入靠的是
// vite.config.ts 里的 unplugin-vue-components。若测试环境不带该插件,
// "组件能否被解析"这类问题在测试里永远暴露不出来:
// 构建会成功,但组件在浏览器里渲染成未知元素(或指令静默失效)。
//
// environment 用 jsdom:单测依赖 document / sessionStorage / Event 等浏览器 API。
export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'jsdom',
      include: ['src/**/*.spec.ts']
    }
  })
)
