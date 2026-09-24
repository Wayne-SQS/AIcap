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
}

async function assertFitAnchorsCanvas(page, viewportSelector, canvasSelector, toolbarIndex = 0) {
  const viewport = page.locator(viewportSelector)
  const canvas = page.locator(canvasSelector)
  await expect(viewport).toBeVisible()
  await expect(canvas).toBeVisible()
  await viewport.scrollIntoViewIfNeeded()
  await viewport.locator('xpath=..').locator('.canvas-zoom-toolbar').nth(toolbarIndex).getByRole('button', { name: '智能适应画布' }).click()
  await expect.poll(() => canvas.evaluate(node => getComputedStyle(node).transform)).not.toBe('none')

  const alignment = await page.evaluate(({ viewportSelector, canvasSelector }) => {
    const viewport = document.querySelector(viewportSelector)
    const canvas = document.querySelector(canvasSelector)
    const viewportRect = viewport.getBoundingClientRect()
    const canvasRect = canvas.getBoundingClientRect()
    return {
      left: canvasRect.left - viewportRect.left - viewport.clientLeft,
      top: canvasRect.top - viewportRect.top - viewport.clientTop,
      scrollLeft: viewport.scrollLeft,
      scrollTop: viewport.scrollTop
    }
  }, { viewportSelector, canvasSelector })

  expect(alignment.left).toBeGreaterThanOrEqual(-1)
  expect(alignment.left).toBeLessThanOrEqual(8)
  expect(alignment.top).toBeGreaterThanOrEqual(-1)
  expect(alignment.top).toBeLessThanOrEqual(8)
  expect(alignment.scrollLeft).toBe(0)
  expect(alignment.scrollTop).toBe(0)
}

test('Fit anchors every project canvas at its upper-left without blank shell padding', async ({ page }) => {
  await login(page)

  await page.goto('/#/board')
  await expect(page.locator('#view-board')).toBeVisible()
  await assertFitAnchorsCanvas(page, '.mapwrap', '.mapgrid')

  await page.goto('/#/gantt')
  await expect(page.locator('#view-gantt')).toBeVisible()
  await assertFitAnchorsCanvas(page, '.gantt-wrap', '.gantt')

  await page.goto('/#/members')
  await expect(page.locator('#view-members')).toBeVisible()
  for (const name of ['overview', 'load', 'capacity', 'activity']) {
    await assertFitAnchorsCanvas(page, `[data-member-canvas="${name}"] .member-module-viewport`, `[data-member-canvas="${name}"] .member-module-canvas`)
  }

  await page.goto('/#/uml')
  await expect(page.locator('#view-uml')).toBeVisible()
  await assertFitAnchorsCanvas(page, '.uml-canvas:not(.sequence-canvas)', '.usecase-svg')
  await assertFitAnchorsCanvas(page, '.sequence-canvas', '.sequence-svg', 1)

  await page.screenshot({ path: 'canvas-fit-alignment.png', fullPage: true })
})
