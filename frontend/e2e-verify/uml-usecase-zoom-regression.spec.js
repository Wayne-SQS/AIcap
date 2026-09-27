import { test, expect } from '@playwright/test'

async function login(page) {
  await page.goto('/#/members')
  await expect(page.locator('#login')).toBeVisible()
  await page.locator('#login-user').fill('李锐铭')
  await page.locator('#login-pass').fill('123456')
  await page.locator('#login-form button[type=submit]').click()
  await expect(page.locator('#login')).toBeHidden()
}

async function usecaseGeometry(page) {
  return page.locator('.usecase-svg').evaluate(svg => {
    const rect = svg.getBoundingClientRect()
    const shellRect = svg.parentElement.getBoundingClientRect()
    const viewport = svg.closest('.usecase-canvas')
    return {
      rect: [rect.x, rect.y, rect.width, rect.height],
      shell: [shellRect.x, shellRect.y, shellRect.width, shellRect.height],
      transform: getComputedStyle(svg).transform,
      contain: getComputedStyle(viewport).contain,
      scroll: [viewport.scrollWidth, viewport.scrollHeight, viewport.scrollLeft, viewport.scrollTop]
    }
  })
}

async function setUsecaseZoom(page, percent) {
  const toolbar = page.locator('.canvas-zoom-toolbar').first()
  const input = toolbar.locator('.canvas-zoom-input')
  await input.fill(String(percent))
  await input.press('Enter')
  await page.waitForTimeout(250)
}

test('use case zoom uses stable integer dimensions across manual, fit, resize and drag', async ({ page }) => {
  await login(page)
  await page.setViewportSize({ width: 2048, height: 1200 })
  await page.goto('/#/uml')
  await expect(page.locator('.usecase-svg')).toBeVisible()
  await page.waitForTimeout(1500)

  const dimensions = []
  for (const percent of [60, 80, 100, 125, 200]) {
    await setUsecaseZoom(page, percent)
    const geometry = await usecaseGeometry(page)
    dimensions.push({ percent, geometry })
    expect(geometry.transform).toBe('none')
    expect(geometry.contain).toBe('layout paint')
    expect(Number.isInteger(geometry.rect[2])).toBeTruthy()
    expect(Number.isInteger(geometry.rect[3])).toBeTruthy()
  }
  console.log('[uml-usecase-zoom:manual]', JSON.stringify(dimensions))

  await setUsecaseZoom(page, 125)
  const beforeResize = await usecaseGeometry(page)
  await page.setViewportSize({ width: 1780, height: 1050 })
  await page.waitForTimeout(500)
  await page.setViewportSize({ width: 2048, height: 1200 })
  await page.waitForTimeout(500)
  const afterResize = await usecaseGeometry(page)
  console.log('[uml-usecase-zoom:resize]', JSON.stringify({ beforeResize, afterResize }))
  expect(afterResize.rect[2]).toBe(beforeResize.rect[2])
  expect(afterResize.rect[3]).toBe(beforeResize.rect[3])

  const fitButton = page.locator('.canvas-zoom-toolbar').first().getByTitle('适应画布')
  await fitButton.click()
  await page.waitForTimeout(500)
  const fit = await usecaseGeometry(page)
  console.log('[uml-usecase-zoom:fit]', JSON.stringify(fit))
  expect(fit.transform).toBe('none')
  expect(Number.isInteger(fit.rect[2])).toBeTruthy()
  expect(Number.isInteger(fit.rect[3])).toBeTruthy()

  for (const percent of [80, 100, 125]) {
    await setUsecaseZoom(page, percent)
    const first = page.locator('.uc-hit').first()
    const before = await first.evaluate(node => {
      const box = node.getBBox()
      return [box.x, box.y]
    })
    const box = await first.boundingBox()
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
    await page.mouse.down()
    await page.mouse.move(box.x + box.width / 2 + 18, box.y + box.height / 2 + 9, { steps: 4 })
    await page.mouse.up()
    const after = await first.evaluate(node => {
      const box = node.getBBox()
      return [box.x, box.y]
    })
    expect(after[0]).not.toBe(before[0])
    expect(after[1]).not.toBe(before[1])
  }
})
