import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // 文档页已经从后端搬到 public/docs，这里只转接口；应用内的
      // /docs/product、/docs/dev 是 Vue 路由，仍然留在 Vite 这边
      '/api': {
        target: 'http://127.0.0.1:8000',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 900
  }
})
