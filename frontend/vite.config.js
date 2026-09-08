import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// base:'./' 使构建产物可用任意静态服务器直接伺服(与旧版 python -m http.server 用法对齐)
export default defineConfig({
  base: './',
  plugins: [vue()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: { port: 5173 },
  preview: { port: 8092 }
})
