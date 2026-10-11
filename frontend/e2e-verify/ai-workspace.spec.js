import { test, expect } from '@playwright/test'

test('AI 工作台可进入独立会议工作区并标明离线样例', async ({ page }) => {
  await page.goto('/#/ai')
  const offlineChoice = page.locator('#login-offline')
  if (await offlineChoice.isVisible().catch(() => false)) await offlineChoice.click()

  await expect(page.getByRole('heading', { name: 'AI 工作台' })).toBeVisible()
  await expect(page.locator('.agent-card')).toHaveCount(6)
  await expect(page.locator('.agent-card').first()).toContainText('当前：离线演示')
  await page.locator('.agent-card').first().getByRole('button', { name: '进入工作区' }).click()

  await expect(page.getByRole('heading', { name: '会议工作区' })).toBeVisible()
  await expect(page.getByText('离线演示 · 静态会议样例 · 非真实模型分析')).toBeVisible()
  await expect(page.getByText('不代表当前录音、真实转写或服务器处理状态')).toBeVisible()
})

test('AI 工作台离线最近本机建议可继续查看审核演示', async ({ page }) => {
  await page.goto('/#/ai')
  const offlineChoice = page.locator('#login-offline')
  if (await offlineChoice.isVisible().catch(() => false)) await offlineChoice.click()

  await expect(page.getByRole('heading', { name: 'AI 工作台' })).toBeVisible()
  await expect(page.getByText('离线演示中的待审建议仅保存在本机')).toBeVisible()
  const resume = page.getByRole('button', { name: '继续查看最近提案' })
  await expect(resume).toBeVisible()
  await resume.click()
  await expect(page.getByRole('heading', { name: '提案审核中心' })).toBeVisible()
  await expect(page.locator('#sug-list [data-sug]').first()).toBeVisible()
})

test('来源会议返回审核中心时保留筛选上下文', async ({ page }) => {
  await page.goto('/#/meetings?meetingId=meeting-7&type=planning&analysisId=analysis-9&fromReview=1&status=reviewed&source=planning&search=scope&fromDate=2026-10-01&toDate=2026-10-08')
  const offlineChoice = page.locator('#login-offline')
  if (await offlineChoice.isVisible().catch(() => false)) await offlineChoice.click()

  await expect(page.getByRole('button', { name: '← 返回审核中心（保留筛选）' })).toBeVisible()
  await expect(page.getByText('从审核中心打开')).toBeVisible()
  await page.getByRole('button', { name: '← 返回审核中心（保留筛选）' }).click()
  await expect(page.getByRole('heading', { name: '提案审核中心' })).toBeVisible()
  const query = await page.evaluate(() => Object.fromEntries(new URLSearchParams(location.hash.split('?')[1])))
  expect(query).toMatchObject({ status: 'reviewed', source: 'planning', search: 'scope', fromDate: '2026-10-01', toDate: '2026-10-08' })
})
