import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const proxy = {
  '/meeting-ai': {
    target: process.env.AICAP_AI_PROXY_TARGET || 'http://127.0.0.1:8090',
    rewrite: path => path.replace(/^\/meeting-ai/, '')
  },
  '/api': { target: 'http://localhost:8080', changeOrigin: true }
}
export default defineConfig({
  base: './', plugins: [vue()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: { port: 5173, proxy },
  preview: { port: 8092, proxy }
})
