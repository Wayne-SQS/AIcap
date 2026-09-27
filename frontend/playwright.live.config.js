import { defineConfig } from '@playwright/test'

// Started only by qa/run_daily_live.py against a fresh disposable database.
export default defineConfig({
  testDir: './e2e-live', workers: 1, timeout: 90000,
  reporter: 'list',
  use: { baseURL: 'http://127.0.0.1:15173', channel: 'msedge', headless: true }
})
