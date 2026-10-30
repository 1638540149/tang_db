import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// SPEC-P8 K200:Vite 构建;产物固定输出到 console/dist,由 FE 内置 HttpServer 直接托管。
export default defineConfig({
  plugins: [vue()],
  base: '/',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    chunkSizeWarningLimit: 2000
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://127.0.0.1:8080',
      '/metrics': 'http://127.0.0.1:8080'
    }
  }
})
