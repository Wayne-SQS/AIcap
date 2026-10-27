import { test, expect } from '@playwright/test'

test.beforeEach(async ({ page }) => {
  await page.route('**/api/health', route => route.fulfill({ status: 503, body: '{}' }))
  await page.route('http://127.0.0.1:8100/health', route => route.fulfill({ status: 503, body: '{}' }))
})

test('AI 办公室展示四个工位，打开会议抽屉后标明离线样例', async ({ page }) => {
  await page.goto('/#/office')
  const offlineChoice = page.locator('#login-offline')
  if (await offlineChoice.isVisible().catch(() => false)) await offlineChoice.click()

  await expect(page.getByRole('heading', { name: 'AI 办公室' })).toBeVisible()
  await expect(page.locator('#view-office .pixel-room')).toBeVisible()
  await expect(page.locator('.room-desk')).toHaveCount(4)
  await page.getByRole('button', { name: '打开译的会议同事' }).click()
  await expect(page.locator('.office-drawer')).toBeVisible()
  await expect(page.getByRole('tab', { name: '成员画像' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '会议工作区' })).toBeVisible()
  await expect(page.getByText('离线演示 · 静态会议样例 · 非真实模型分析')).toBeVisible()
  // 长免责句在 ac741a6「simplify UI copy」里被删，改为断言演示徽章仍在（口径不变：这是演示样例）
  await expect(page.locator('.rec .sug-status')).toHaveText('演示数据')
})

test('AI 办公室离线最近本机建议可继续查看审核演示', async ({ page }) => {
  await page.goto('/#/office')
  const offlineChoice = page.locator('#login-offline')
  if (await offlineChoice.isVisible().catch(() => false)) await offlineChoice.click()

  await expect(page.getByRole('heading', { name: 'AI 办公室' })).toBeVisible()
  await expect(page.getByText('离线演示数据保存在本机')).toBeVisible()
  const resume = page.getByRole('button', { name: '继续查看最近提案' })
  await expect(resume).toBeVisible()
  await resume.click()
  await expect(page.getByRole('heading', { name: '提案审核中心' })).toBeVisible()
  await expect(page.locator('#sug-list [data-sug]').first()).toBeVisible()
})

test('旧会议入口重定向进办公室且来源会议返回审核中心保留筛选', async ({ page }) => {
  await page.goto('/#/meetings?meetingId=meeting-7&type=planning&analysisId=analysis-9&fromReview=1&status=reviewed&source=planning&search=scope&fromDate=2026-10-01&toDate=2026-10-08')
  const offlineChoice = page.locator('#login-offline')
  if (await offlineChoice.isVisible().catch(() => false)) await offlineChoice.click()

  await expect(page).toHaveURL(/#\/office\?/)
  await expect(page.getByRole('button', { name: '← 返回审核中心（保留筛选）' })).toBeVisible()
  await expect(page.getByText('从审核中心打开')).toBeVisible()
  await page.getByRole('button', { name: '← 返回审核中心（保留筛选）' }).click()
  await expect(page.getByRole('heading', { name: '提案审核中心' })).toBeVisible()
  const query = await page.evaluate(() => Object.fromEntries(new URLSearchParams(location.hash.split('?')[1])))
  expect(query).toMatchObject({ status: 'reviewed', source: 'planning', search: 'scope', fromDate: '2026-10-01', toDate: '2026-10-08' })
})
