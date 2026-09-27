import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const aiProxy = { '/meeting-ai': { target: process.env.AICAP_AI_PROXY_TARGET || 'http://127.0.0.1:8090', rewrite: path => path.replace(/^\/meeting-ai/, '') } }
// base:'./' 使构建产物可用任意静态服务器直接伺服(与旧版 python -m http.server 用法对齐)
export default defineConfig({
  base: './',
  plugins: [vue()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: {
    port: 5173,
    proxy: aiProxy
  },
  preview: { port: 8092, proxy: aiProxy }
})
