import { defineConfig } from '@playwright/test'

/**
 * 爱管理 · 当前基线(Java Spring Boot 后端 + Vue3 前端)前端 E2E 回归配置。
 *
 * 前置(必须,套件直连真实服务,不做任何 mock 后端):
 *   1) java-backend(Spring Boot 3.5.3)在 http://localhost:8080 运行,连开发库 AIcap
 *   2) frontend Vite dev server 在 http://localhost:5173 运行(npm run dev)
 *   3) 数据基线:US01–US37 / T01–T16 / 5 名真实成员;写操作由用例自行唯一命名并清理
 *
 * 用系统自带 Edge(channel: msedge),不下载 Playwright 浏览器;
 * 虚拟麦克风参数保留(本套件不真录音,音频用例走生成的 .mp3 上传链路)。
 *
 * 运行:cd qa && npx playwright test
 */
const API_BASE = process.env.QA_API_BASE || 'http://localhost:8080'

export default defineConfig({
  testDir: '.',
  /* 只跑当前基线的回归套件:qa/ 下另有 5 个针对已归档 legacy/index.html + FastAPI 的旧 spec,
     它们不是本工程的一部分,显式排除在收集范围外(文件保留归档,不删除) */
  testMatch: 'vue-baseline-e2e.spec.js',
  timeout: 120000,
  expect: { timeout: 15000 },
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: [['list'], ['html', { outputFolder: 'report', open: 'never' }]],
  outputDir: 'test-results',
  use: {
    baseURL: 'http://localhost:5173',
    channel: 'msedge',
    headless: true,
    permissions: ['microphone'],
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    extraHTTPHeaders: {},
    launchOptions: {
      args: [
        '--use-fake-ui-for-media-stream',
        '--use-fake-device-for-media-stream',
        '--autoplay-policy=no-user-gesture-required'
      ]
    }
  },
  metadata: { apiBase: API_BASE }
})
