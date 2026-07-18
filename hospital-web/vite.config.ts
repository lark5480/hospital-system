import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 开发期通过 Vite proxy 把 /api 转发到 API Gateway(:8104),
// 这样前端始终走 Gateway,与部署形态(gateway 统一入口)保持一致。
export default defineConfig({
  plugins: [vue()],
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
