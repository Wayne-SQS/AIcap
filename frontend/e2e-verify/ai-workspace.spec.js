import { test, expect } from '@playwright/test'

/* 2026-10-19 办公室改版后的断言口径:
   - 布局是「门牌行 + 两栏」:左 = 主管对话(.office-chat)+ 白板抽屉(.office-feed,
     头部常驻、体默认收起);右 = 三张工位卡(译/衡/绘,卡即 tab,aria-label 为
     「译 · 会议」格式)+ 工作面板。MeetingView 以 embedded 模式嵌入,无页级 hero,
     「会议工作区」heading 不复存在 —— 默认进译的会议页改由工位 tab 选中态断言。
   - 「继续查看最近提案」按钮已删,离线最近建议入口是门牌行的「待审 N」徽标。 */
test('AI 办公室两栏齐备且默认进译的会议工位并标明离线样例', async ({ page }) => {
  await page.goto('/#/office')
  const offlineChoice = page.locator('#login-offline')
  if (await offlineChoice.isVisible().catch(() => false)) await offlineChoice.click()

  await expect(page.getByRole('heading', { name: 'AI 办公室' })).toBeVisible()
  await expect(page.locator('#view-office .office-chat')).toBeVisible()
  await expect(page.locator('#view-office .office-feed')).toBeVisible()
  await expect(page.getByRole('tab', { name: '译 · 会议' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '衡 · 画像' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '绘 · 规划' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '译 · 会议' })).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByText('离线演示 · 静态会议样例 · 非真实模型分析')).toBeVisible()
  await expect(page.getByText('不代表当前录音、真实转写或服务器处理状态')).toBeVisible()
})

test('AI 办公室离线最近本机建议可继续查看审核演示', async ({ page }) => {
  await page.goto('/#/office')
  const offlineChoice = page.locator('#login-offline')
  if (await offlineChoice.isVisible().catch(() => false)) await offlineChoice.click()

  await expect(page.getByRole('heading', { name: 'AI 办公室' })).toBeVisible()
  await expect(page.getByText('离线演示中的待审建议仅保存在本机')).toBeVisible()
  const reviewLink = page.locator('#view-office .of-review-link')
  await expect(reviewLink).toBeVisible()
  await reviewLink.click()
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
