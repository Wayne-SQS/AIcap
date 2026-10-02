import { defineConfig } from '@playwright/test'

// Started only by qa/run_daily_live.py against a fresh disposable database.
export default defineConfig({
  testDir: './e2e-live', workers: 1, timeout: 90000,
  outputDir: process.env.AICAP_LIVE_ARTIFACT_DIR ? `${process.env.AICAP_LIVE_ARTIFACT_DIR}/playwright` : './test-results/live',
  reporter: 'list',
  use: { baseURL: 'http://127.0.0.1:15173', channel: 'msedge', headless: true }
})
