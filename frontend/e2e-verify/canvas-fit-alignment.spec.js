import { test, expect } from '@playwright/test'

const ADMIN = '李锐铭'
const PASSWORD = '123456'

async function login(page) {
  // 多项目改造后项目号是业务接口的必填上下文,先预置项目号再登录
  await page.addInitScript(() => localStorage.setItem('aiguanli-current-project', 'P001'))
  await page.goto('/#/members')
  await expect(page.locator('#login')).toBeVisible()
  await page.locator('#login-user').fill(ADMIN)
  await page.locator('#login-pass').fill(PASSWORD)
  await page.locator('#login-form button[type=submit]').click()
  await expect(page.locator('#login')).toBeHidden()
}

async function assertFitAnchorsCanvas(page, viewportSelector, canvasSelector, toolbarIndex = 0, renderMode = 'transform') {
  const viewport = page.locator(viewportSelector)
  const canvas = page.locator(canvasSelector)
  await expect(viewport).toBeVisible()
  await expect(canvas).toBeVisible()
  await viewport.scrollIntoViewIfNeeded()
  await viewport.locator('xpath=..').locator('.canvas-zoom-toolbar').nth(toolbarIndex).getByRole('button', { name: '智能适应画布' }).click()
  if (renderMode === 'dimensions') {
    // 用例图走 renderMode: 'dimensions',transform 恒为 none,靠 width/height 缩放;
    // 用「画布尺寸已落回视口宽度内」作为 fit 生效的等价证明
    await expect.poll(() => page.evaluate(({ viewportSelector, canvasSelector }) => {
      const viewport = document.querySelector(viewportSelector)
      const canvas = document.querySelector(canvasSelector)
      return canvas.getBoundingClientRect().width <= viewport.clientWidth + 1
    }, { viewportSelector, canvasSelector })).toBe(true)
  } else {
    await expect.poll(() => canvas.evaluate(node => getComputedStyle(node).transform)).not.toBe('none')
  }

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
  await assertFitAnchorsCanvas(page, '.uml-canvas:not(.sequence-canvas)', '.usecase-svg', 0, 'dimensions')
  await assertFitAnchorsCanvas(page, '.sequence-canvas', '.sequence-svg', 1)

  await page.screenshot({ path: 'canvas-fit-alignment.png', fullPage: true })
})
