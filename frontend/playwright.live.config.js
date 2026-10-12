import { defineConfig } from '@playwright/test'

// Defaults target qa/run_daily_live.py's disposable stack. Deploy verification
// overrides the origin so the same scenarios exercise the Nginx entry point.
export default defineConfig({
  testDir: './e2e-live', workers: 1, timeout: 90000,
  outputDir: process.env.AICAP_LIVE_ARTIFACT_DIR ? `${process.env.AICAP_LIVE_ARTIFACT_DIR}/playwright` : './test-results/live',
  reporter: 'list',
  use: { baseURL: process.env.AICAP_LIVE_WEB_BASE || 'http://127.0.0.1:15173', channel: 'msedge', headless: true }
})
