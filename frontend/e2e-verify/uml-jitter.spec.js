import { test, expect } from "@playwright/test"

test("UML canvases keep stable bounds after initial fit", async ({ page }) => {
  await page.goto("/#/members")
  await expect(page.locator("#login")).toBeVisible()
  await page.locator("#login-user").fill("李锐铭")
  await page.locator("#login-pass").fill("123456")
  await page.locator("#login-form button[type=submit]").click()
  await expect(page.locator("#login")).toBeHidden()
  await page.setViewportSize({ width: 2048, height: 1200 })
  await page.goto("/#/uml")
  await expect(page.locator("#view-uml")).toBeVisible()
  await expect(page.locator(".usecase-svg")).toBeVisible()
  await expect(page.locator(".sequence-svg")).toBeVisible()
  const ellipse = page.locator(".uc-hit ellipse").first()
  const ellipseBox = await ellipse.boundingBox()
  await page.evaluate(() => {
    window.__umlHoverEvents = 0
    const usecase = document.querySelector(".uc-hit")
    usecase.addEventListener("mouseenter", () => { window.__umlHoverEvents++ })
    usecase.addEventListener("mouseleave", () => { window.__umlHoverEvents++ })
  })
  await page.mouse.move(ellipseBox.x + 2, ellipseBox.y + ellipseBox.height / 2)
  await page.waitForTimeout(1500)
  console.log("[uml-hover-event-count]", await page.evaluate(() => window.__umlHoverEvents))
  expect(await page.evaluate(() => window.__umlHoverEvents)).toBe(1)

  const samples = await page.evaluate(async () => {
    const nodes = [".uml-canvas:not(.sequence-canvas)", ".usecase-svg", ".sequence-canvas", ".sequence-svg"]
      .map(selector => document.querySelector(selector))
    const result = []
    for (let i = 0; i < 30; i++) {
      result.push(nodes.map(node => {
        const rect = node.getBoundingClientRect()
        return [rect.x, rect.y, rect.width, rect.height, node.clientWidth, node.clientHeight, node.scrollWidth, node.scrollHeight, node.getAttribute("viewBox")]
      }))
      await new Promise(resolve => setTimeout(resolve, 100))
    }
    return result
  })

  const ranges = samples[0].map((_, index) => samples.reduce((range, sample) => {
    const values = sample[index]
    values.slice(0, 8).forEach((value, metric) => {
      range.min[metric] = Math.min(range.min[metric], value)
      range.max[metric] = Math.max(range.max[metric], value)
    })
    range.views.add(values[8])
    return range
  }, { min: Array(8).fill(Infinity), max: Array(8).fill(-Infinity), views: new Set() }))
  console.log("[uml-jitter-ranges]", JSON.stringify(ranges.map(range => ({
    delta: range.max.map((value, index) => Number((value - range.min[index]).toFixed(3))),
    viewBoxes: [...range.views]
  }))))
  expect(ranges.every(range => range.max.every((value, index) => value - range.min[index] < 0.5) && range.views.size === 1)).toBeTruthy()
})
