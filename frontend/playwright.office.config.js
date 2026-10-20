import { defineConfig } from '@playwright/test'
import base from './playwright.verify.config.js'

export default defineConfig({
  ...base,
  testMatch: ['office-design.spec.js', 'ai-workspace.spec.js'],
  timeout: 45000,
  expect: { timeout: 10000 },
  use: { ...base.use, baseURL: 'http://127.0.0.1:5182' },
  webServer: { ...base.webServer, command: 'node ./node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5182 --strictPort', url: 'http://127.0.0.1:5182' }
})
