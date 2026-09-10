import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { fileURLToPath, URL } from 'node:url'

// 开发期通过 Vite proxy 把 /api 转发到 API Gateway(:8104),
// 这样前端始终走 Gateway,与部署形态(gateway 统一入口)保持一致。
export default defineConfig({
  plugins: [
    vue(),
    // R-39: Element Plus 组件按需引入(模板里的 <el-xxx> 与 v-loading 指令自动解析),
    // 取代原先 main.ts 里的 app.use(ElementPlus) 全量注册。
    //
    // 两个刻意的取舍:
    //  1) importStyle: false —— 样式仍由 main.ts 全量引入 element-plus/dist/index.css。
    //     因为 styles/element.css 的 --el-* 主题映射层明确要求它在 index.css 之后加载,
    //     改成按组件引样式会打乱这个顺序;
    //  2) 不引入 unplugin-auto-import —— 各文件里的 ElMessage / ElMessageBox 都是显式 import,
    //     显式 import 不经过 resolver,只有全量 CSS 才能保证这些命令式弹窗有样式。
    // 3) dts: false —— 刻意不生成 src/components.d.ts。
    //     生成它会让 vue-tsc 开始对 el-* 组件做严格的 props 检查,立刻暴露 34 个
    //     既有类型问题(主要是 el-table 作用域插槽的 row 被推断为 DefaultRow,
    //     以及 el-tag :type 传入了含空串的联合类型)。
    //     那些问题本身值得修,但属于独立任务;在按需引入这一步顺手打开会让
    //     "type-check 0 错误" 的基线被打破,掩盖本次改动的真实效果。
    //     修完那 34 处后,把 dts 改回 'src/components.d.ts' 即可获得组件级类型安全。
    Components({
      resolvers: [ElementPlusResolver({ importStyle: false })],
      dts: false
    })
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8104',
        changeOrigin: true
      }
    }
  }
})
