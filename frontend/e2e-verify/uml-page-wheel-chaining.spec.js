import { test, expect } from '@playwright/test'

async function login(page) {
  await page.goto('/#/members')
  await expect(page.locator('#login')).toBeVisible()
  await page.locator('#login-user').fill('李锐铭')
  await page.locator('#login-pass').fill('123456')
  await page.locator('#login-form button[type=submit]').click()
  await expect(page.locator('#login')).toBeHidden()
}

test('ordinary wheel scroll chains from use case content to the page', async ({ page }) => {
  await login(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/#/uml')
  await expect(page.locator('.usecase-svg')).toBeVisible()
  await page.waitForTimeout(1500)

  const viewport = page.locator('.usecase-canvas')
  const targets = await page.evaluate(() => {
    const svg = document.querySelector('.usecase-svg')
    const viewport = document.querySelector('.usecase-canvas')
    const rect = node => {
      const box = node.getBoundingClientRect()
      return { x: box.x + box.width / 2, y: box.y + box.height / 2 }
    }
    const line = svg.querySelector('.association')
    const point = line.getPointAtLength(line.getTotalLength() * 0.55)
    const matrix = line.getScreenCTM()
    const linePoint = new DOMPoint(point.x, point.y).matrixTransform(matrix)
    const viewportRect = viewport.getBoundingClientRect()
    return {
      blank: { x: viewportRect.left + viewport.clientWidth * 0.52, y: viewportRect.top + viewport.clientHeight * 0.86 },
      usecase: rect(svg.querySelector('.uc-hit')),
      actor: rect(svg.querySelector('.actor-hit')),
      line: { x: linePoint.x, y: linePoint.y },
      legend: rect(svg.querySelector('.legend-object'))
    }
  })

  const initial = await page.evaluate(() => ({
    scrollY: window.scrollY,
    zoom: document.querySelector('.usecase-svg').style.width,
    overscrollY: getComputedStyle(document.querySelector('.usecase-canvas')).overscrollBehaviorY,
    viewportOverflowY: getComputedStyle(document.querySelector('.usecase-canvas')).overflowY
  }))
  expect(initial.overscrollY).toBe('auto')
  const outcomes = []
  for (const [name, point] of Object.entries(targets)) {
    await page.evaluate(() => { window.scrollTo(0, 0) })
    await page.waitForTimeout(450)
    const before = await page.evaluate(() => window.scrollY)
    await page.mouse.move(point.x, point.y)
    await page.mouse.wheel(0, 480)
    await page.waitForTimeout(700)
    const after = await page.evaluate(() => window.scrollY)
    const inner = await viewport.evaluate(node => [node.scrollTop, node.scrollHeight, node.clientHeight])
    outcomes.push({ name, before, after, inner })
    expect(after, `${name}: page did not scroll with pointer over the use case diagram`).toBeGreaterThan(before)
  }
  console.log('[uml-wheel:targets]', JSON.stringify({ initial, outcomes }))

  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
  await page.waitForTimeout(450)
  const bottom = await page.evaluate(() => window.scrollY)
  await page.mouse.move(targets.blank.x, targets.blank.y)
  await page.mouse.wheel(0, -480)
  await page.waitForTimeout(700)
  const afterUp = await page.evaluate(() => window.scrollY)
  expect(afterUp).toBeLessThan(bottom)
  await page.locator('.canvas-zoom-toolbar').first().locator('.canvas-zoom-input').fill('200')
  await page.locator('.canvas-zoom-toolbar').first().locator('.canvas-zoom-input').press('Enter')
  await page.waitForTimeout(300)
  const beforeWheelZoom = await page.locator('.usecase-svg').evaluate(node => [node.style.width, node.style.height])
  await page.mouse.move(targets.blank.x, targets.blank.y)
  await page.mouse.wheel(0, 320)
  await page.waitForTimeout(500)
  const afterWheelZoom = await page.locator('.usecase-svg').evaluate(node => [node.style.width, node.style.height])
  expect(afterWheelZoom).toEqual(beforeWheelZoom)
})

test('ordinary wheel scroll chains from sequence content to the page', async ({ page }) => {
  await login(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/#/uml')
  await expect(page.locator('.sequence-svg')).toBeVisible()
  await page.waitForTimeout(1500)

  const viewport = page.locator('.sequence-canvas')
  const sequenceToolbar = page.locator('.canvas-zoom-toolbar').nth(1)
  await sequenceToolbar.locator('.canvas-zoom-input').fill('200')
  await sequenceToolbar.locator('.canvas-zoom-input').press('Enter')
  await page.waitForTimeout(400)
  const overflow = await viewport.evaluate(node => ({
    y: getComputedStyle(node).overscrollBehaviorY,
    scrollHeight: node.scrollHeight,
    clientHeight: node.clientHeight
  }))
  expect(overflow.y).toBe('auto')
  expect(overflow.scrollHeight).toBeGreaterThan(overflow.clientHeight)

  await page.evaluate(() => {
    const viewport = document.querySelector('.sequence-canvas')
    viewport.scrollTop = 0
    viewport.scrollIntoView({ block: 'center' })
    window.__sequenceZoomBeforeWheel = document.querySelector('.sequence-svg').style.transform
  })
  await page.waitForTimeout(900)
  const point = await viewport.evaluate(node => {
    const rect = node.getBoundingClientRect()
    return { x: rect.left + rect.width / 2, y: Math.max(rect.top + 8, Math.min(rect.bottom - 8, window.innerHeight / 2)), rect: [rect.left, rect.top, rect.width, rect.height], pageY: window.scrollY }
  })
  const hit = await page.evaluate(({ x, y }) => {
    const node = document.elementFromPoint(x, y)
    return { className: node?.getAttribute('class') || node?.tagName, inViewport: !!node?.closest('.sequence-canvas') }
  }, point)
  console.log('[uml-wheel:sequence-target]', JSON.stringify({ point, hit }))
  expect(hit.inViewport).toBeTruthy()
  await page.waitForTimeout(400)
  await page.mouse.move(point.x, point.y)
  await page.mouse.wheel(0, 420)
  await page.waitForTimeout(600)
  const internalScroll = await viewport.evaluate(node => node.scrollTop)
  expect(internalScroll).toBeGreaterThan(0)

  await viewport.evaluate(node => { node.scrollTop = node.scrollHeight })
  await page.waitForTimeout(300)
  const pageBefore = await page.evaluate(() => window.scrollY)
  await page.mouse.wheel(0, 420)
  await page.waitForTimeout(700)
  const pageAfter = await page.evaluate(() => window.scrollY)
  expect(pageAfter).toBeGreaterThan(pageBefore)

  await page.evaluate(() => { document.querySelector('.sequence-canvas').scrollTop = 0 })
  await viewport.scrollIntoViewIfNeeded()
  await page.waitForTimeout(450)
  const pageBeforeUp = await page.evaluate(() => window.scrollY)
  await page.mouse.wheel(0, -420)
  await page.waitForTimeout(700)
  const pageAfterUp = await page.evaluate(() => window.scrollY)
  expect(pageAfterUp).toBeLessThan(pageBeforeUp)

  const zoomAfterWheel = await page.locator('.sequence-svg').evaluate(node => node.style.transform)
  expect(zoomAfterWheel).toBe(await page.evaluate(() => window.__sequenceZoomBeforeWheel))
})
