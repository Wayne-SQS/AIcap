import { defineConfig } from '@playwright/test'

/**
 * 新功能验收配置(成员画像 + 麦克风录音)。
 * - 用系统自带 Edge(channel: msedge),无需下载 Playwright 浏览器
 * - 用虚拟麦克风设备,无需真人对着麦克风说话即可真实跑通 MediaRecorder → mp3 编码
 * 运行:npm run e2e:verify；配置会自动启动 Vite。需要真实服务的用例仍需后端 8080。
 */
export default defineConfig({
  testDir: './e2e-verify',
  timeout: 180000,
  expect: { timeout: 20000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:5173',
    channel: 'msedge',
    headless: true,
    permissions: ['microphone'],
    launchOptions: {
      args: [
        '--use-fake-ui-for-media-stream',
        '--use-fake-device-for-media-stream',
        '--autoplay-policy=no-user-gesture-required'
      ]
    }
  },
  webServer: {
    // 直接启动 Vite Node 入口，避免 Windows 下 npm.cmd 留下不可回收的子进程。
    command: 'node ./node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5173 --strictPort',
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: false
  }
})
