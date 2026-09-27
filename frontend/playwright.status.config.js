import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e-status',
  timeout: 30000,
  workers: 1,
  reporter: 'list',
  use: { baseURL: 'http://127.0.0.1:5187', channel: 'msedge', headless: true },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 5187 --strictPort',
    url: 'http://127.0.0.1:5187',
    env: { AICAP_AI_PROXY_TARGET: 'http://127.0.0.1:18090' },
    reuseExistingServer: false
  }
})
