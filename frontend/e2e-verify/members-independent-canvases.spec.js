import { test, expect } from '@playwright/test'

const ADMIN = '李锐铭'
const PASSWORD = '123456'

async function login(page) {
  await page.goto('/#/members')
  await expect(page.locator('#login')).toBeVisible()
  await page.locator('#login-user').fill(ADMIN)
  await page.locator('#login-pass').fill(PASSWORD)
  await page.locator('#login-form button[type=submit]').click()
  await expect(page.locator('#login')).toBeHidden()
  await expect(page.locator('#view-members')).toBeVisible()
}

test('Members independent canvases keep zoom, fit, scroll and owner confirmation isolated', async ({ page }) => {
  await login(page)

  const sections = page.locator('.member-canvas-section')
  await expect(sections).toHaveCount(4)
  await expect(page.locator('.member-module-viewport')).toHaveCount(4)
  await expect(page.locator('.canvas-zoom-toolbar')).toHaveCount(4)

  const structure = await page.locator('.member-canvas-list').evaluate(list => ({
    children: [...list.children].map(node => node.dataset.memberCanvas),
    viewportParents: [...list.querySelectorAll('.member-module-viewport')].map(node => node.closest('.member-canvas-section')?.dataset.memberCanvas),
    listOverflow: getComputedStyle(list).overflow
  }))
  expect(structure.children).toEqual(['overview', 'load', 'capacity', 'activity'])
  expect(structure.viewportParents).toEqual(['overview', 'load', 'capacity', 'activity'])
  expect(structure.listOverflow).toBe('visible')

  const geometry = await sections.evaluateAll(nodes => nodes.map(node => {
    const rect = node.getBoundingClientRect()
    return { top: rect.top + scrollY, bottom: rect.bottom + scrollY }
  }))
  expect(geometry.every((rect, index) => index === 0 || rect.top - geometry[index - 1].bottom >= 24)).toBeTruthy()

  const inputs = page.locator('.member-canvas-section .canvas-zoom-input')
  for (const [index, value] of ['60', '80', '125', '100'].entries()) {
    await inputs.nth(index).fill(value)
    await inputs.nth(index).press('Enter')
  }
  expect(await Promise.all([0, 1, 2, 3].map(index => inputs.nth(index).inputValue()))).toEqual(['60', '80', '125', '100'])

  for (let index = 0; index < 4; index++) {
    const before = await Promise.all([0, 1, 2, 3].map(item => inputs.nth(item).inputValue()))
    await sections.nth(index).getByRole('button', { name: '智能适应画布' }).click()
    const after = await Promise.all([0, 1, 2, 3].map(item => inputs.nth(item).inputValue()))
    for (let other = 0; other < 4; other++) {
      if (other !== index) expect(after[other]).toBe(before[other])
    }
  }

  for (const [index, value] of ['60', '80', '125', '100'].entries()) {
    await inputs.nth(index).fill(value)
    await inputs.nth(index).press('Enter')
  }
  expect(await Promise.all([0, 1, 2, 3].map(index => inputs.nth(index).inputValue()))).toEqual(['60', '80', '125', '100'])

  await page.evaluate(() => window.scrollTo(0, 0))
  const pageHeight = await page.evaluate(() => document.documentElement.scrollHeight)
  expect(pageHeight).toBeGreaterThan(page.viewportSize().height * 2)
  for (const viewport of await page.locator('.member-module-viewport').all()) {
    await viewport.scrollIntoViewIfNeeded()
    await viewport.evaluate(node => { node.scrollTop = node.scrollHeight })
    const beforePageScroll = await page.evaluate(() => window.scrollY)
    const box = await viewport.boundingBox()
    await page.mouse.move(box.x + box.width / 2, box.y + Math.min(box.height - 20, 120))
    await page.mouse.wheel(0, 520)
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(beforePageScroll)
  }

  const fromTask = page.locator('.member-task-card').filter({ hasText: 'T02' })
  const targetMember = page.locator('[data-owner-drop="1"]')
  const dataTransfer = await page.evaluateHandle(() => new DataTransfer())
  await fromTask.dispatchEvent('dragstart', { dataTransfer })
  await targetMember.dispatchEvent('dragenter', { dataTransfer })
  await targetMember.dispatchEvent('dragover', { dataTransfer })
  await targetMember.dispatchEvent('drop', { dataTransfer })
  await fromTask.dispatchEvent('dragend', { dataTransfer })
  const dialog = page.locator('.risk-dialog')
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('T02')
  await dialog.getByRole('button', { name: '取消调整' }).click()
  await expect(dialog).toBeHidden()

  await page.screenshot({ path: 'members-independent-canvases.png', fullPage: true })

  console.log('[members-canvases] four sections, independent zoom 60/80/125/100, isolated fit, page scroll, owner-risk cancel passed')
})
