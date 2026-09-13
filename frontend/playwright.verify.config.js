import { defineConfig } from '@playwright/test'

/**
 * 新功能验收配置(成员画像 + 麦克风录音)。
 * - 用系统自带 Edge(channel: msedge),无需下载 Playwright 浏览器
 * - 用虚拟麦克风设备,无需真人对着麦克风说话即可真实跑通 MediaRecorder → mp3 编码
 * 运行:npm run e2e:verify（前置:后端 8080 与 vite dev 5173 已启动）
 */
export default defineConfig({
  testDir: './e2e-verify',
  timeout: 180000,
  expect: { timeout: 20000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:5173',
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
  }
})
