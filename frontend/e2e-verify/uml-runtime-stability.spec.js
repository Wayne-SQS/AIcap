import { test, expect } from '@playwright/test'
import crypto from 'node:crypto'

const ADMIN = '李锐铭'

async function login(page) {
  await page.goto('/#/members')
  await expect(page.locator('#login')).toBeVisible()
  await page.locator('#login-user').fill(ADMIN)
  await page.locator('#login-pass').fill('123456')
  await page.locator('#login-form button[type=submit]').click()
  await expect(page.locator('#login')).toBeHidden()
}

async function collectPhase(page, name, point, duration = 5000) {
  await page.mouse.move(point.x, point.y)
  await page.waitForTimeout(150)
  await page.evaluate(() => { if (window.__umlMutations) window.__umlMutations.length = 0 })
  const result = await page.evaluate(async ({ name, duration }) => {
    const svg = document.querySelector('.usecase-svg')
    const viewport = document.querySelector('.uml-canvas:not(.sequence-canvas)')
    const shell = viewport?.querySelector('.canvas-zoom-shell')
    const system = svg?.querySelector('.system-boundary')
    const first = svg?.querySelector('.uc-hit')
    const values = []
    const start = Date.now()
    while (Date.now() - start < duration) {
      const rect = node => {
        const { x, y, width, height } = node.getBoundingClientRect()
        return [x, y, width, height]
      }
      const box = node => {
        const { x, y, width, height } = node.getBBox()
        return [x, y, width, height]
      }
      values.push({
        svgRect: rect(svg), viewBox: svg.getAttribute('viewBox'), systemBox: box(system),
        firstUsecaseBox: box(first), viewportRect: rect(viewport),
        client: [viewport.clientWidth, viewport.clientHeight],
        scroll: [viewport.scrollWidth, viewport.scrollHeight, viewport.scrollLeft, viewport.scrollTop],
        zoom: svg.style.transform, shellRect: rect(shell),
        shellStyle: [shell.style.width, shell.style.height, shell.style.paddingLeft, shell.style.paddingTop],
        resizeCount: window.__umlResizeCount, rafCount: window.__umlRafCount
      })
      await new Promise(resolve => setTimeout(resolve, 100))
    }
    const fields = Object.keys(values[0] || {})
    return { name, count: values.length, metrics: Object.fromEntries(fields.map(field => {
      const unique = [...new Set(values.map(value => JSON.stringify(value[field])))]
      return [field, { uniqueCount: unique.length, examples: unique.slice(0, 6) }]
    })), mutations: window.__umlMutations || [] }
  }, { name, duration })
  console.log(`[uml-runtime:${name}]`, JSON.stringify(result))
  return result
}

test('UML use case canvas stays stable while idle and across hover targets', async ({ page }) => {
  await login(page)
  await page.setViewportSize({ width: 2048, height: 1200 })
  await page.goto('/#/uml')
  const svg = page.locator('.usecase-svg')
  const viewport = page.locator('.uml-canvas:not(.sequence-canvas)')
  await expect(svg).toBeVisible()
  await page.waitForTimeout(2000)

  await page.evaluate(() => {
    window.__umlResizeCount = 0
    window.__umlRafCount = 0
    const viewport = document.querySelector('.uml-canvas:not(.sequence-canvas)')
    new ResizeObserver(() => { window.__umlResizeCount++ }).observe(viewport)
    const original = window.requestAnimationFrame.bind(window)
    window.requestAnimationFrame = callback => original(time => {
      window.__umlRafCount++
      callback(time)
    })
    const svg = document.querySelector('.usecase-svg')
    const firstUsecase = svg.querySelector('.uc-hit')
    const firstActor = svg.querySelector('.actor-hit')
    const firstLine = svg.querySelector('.association')
    const identity = { svg, firstUsecase, firstActor, firstLine }
    window.__umlIdentity = identity
    window.__umlMutations = []
    new MutationObserver(records => {
      window.__umlMutations.push(...records.map(record => ({
        type: record.type,
        attribute: record.attributeName || '',
        target: record.target.className?.baseVal || record.target.tagName
      })))
    }).observe(svg, { attributes: true, childList: true, subtree: true })
  })

  const vp = await viewport.boundingBox()
  const ellipse = await page.locator('.uc-hit ellipse').first().boundingBox()
  const phases = [
    await collectPhase(page, 'idle', { x: 8, y: 8 }, 10000),
    await collectPhase(page, 'blank', { x: vp.x + vp.width * 0.88, y: vp.y + vp.height * 0.88 }),
    await collectPhase(page, 'usecase-center', { x: ellipse.x + ellipse.width / 2, y: ellipse.y + ellipse.height / 2 }),
    await collectPhase(page, 'usecase-edge', { x: ellipse.x + 2, y: ellipse.y + ellipse.height / 2 }),
    await collectPhase(page, 'actor-nearby', { x: vp.x + vp.width * 0.07, y: vp.y + vp.height * 0.15 }),
    await collectPhase(page, 'scrollbar-edge', { x: vp.x + vp.width - 5, y: vp.y + vp.height / 2 })
  ]
  expect(phases).toHaveLength(6)
  console.log('[uml-runtime:phase-mutations]', JSON.stringify(phases.map(phase => ({ name: phase.name, count: phase.mutations.length, attributes: [...new Set(phase.mutations.map(item => item.attribute).filter(Boolean))] }))))
  expect(phases[0].mutations).toEqual([])
  const stableMetrics = ['svgRect', 'viewBox', 'systemBox', 'firstUsecaseBox', 'viewportRect', 'client', 'scroll', 'zoom', 'shellRect', 'shellStyle', 'resizeCount']
  for (const phase of phases) {
    for (const metric of stableMetrics) {
      expect(phase.metrics[metric].uniqueCount, `${phase.name}: ${metric} changed: ${JSON.stringify(phase.metrics[metric].examples)}`).toBe(1)
    }
  }

  const runtime = await page.evaluate(() => ({
    identity: {
      svg: document.querySelector('.usecase-svg') === window.__umlIdentity.svg,
      firstUsecase: document.querySelector('.uc-hit') === window.__umlIdentity.firstUsecase,
      firstActor: document.querySelector('.actor-hit') === window.__umlIdentity.firstActor,
      firstLine: document.querySelector('.association') === window.__umlIdentity.firstLine
    },
    mutations: window.__umlMutations,
    resizeCount: window.__umlResizeCount,
    rafCount: window.__umlRafCount
  }))
  console.log('[uml-runtime:identity-mutations]', JSON.stringify({
    identity: runtime.identity,
    mutationCount: runtime.mutations.length,
    mutationAttributes: [...new Set(runtime.mutations.map(item => item.attribute).filter(Boolean))],
    resizeCount: runtime.resizeCount,
    rafCount: runtime.rafCount
  }))
  expect(Object.values(runtime.identity).every(Boolean)).toBeTruthy()
  expect(runtime.mutations.length).toBeGreaterThanOrEqual(0)

  await page.waitForTimeout(1500)
  const captureStableShots = async (point, name) => {
    await page.mouse.move(point.x, point.y)
    await page.waitForTimeout(400)
    const shots = []
    for (let i = 0; i < 6; i++) {
      shots.push(crypto.createHash('sha256').update(await page.locator('.usecase-svg').screenshot()).digest('hex'))
      await page.waitForTimeout(500)
    }
    console.log(`[uml-runtime:${name}-screenshot-hashes]`, shots)
    return shots
  }
  const idleShots = []
  for (let i = 0; i < 10; i++) {
    const shot = await page.locator('.usecase-svg').screenshot({ path: `test-results/uml-idle-${i}.png` })
    idleShots.push(crypto.createHash('sha256').update(shot).digest('hex'))
    await page.waitForTimeout(500)
  }
  console.log('[uml-runtime:idle-screenshot-hashes]', idleShots)
  expect(new Set(idleShots.slice(1)).size).toBe(1)
  await captureStableShots({ x: vp.x + vp.width * 0.88, y: vp.y + vp.height * 0.88 }, 'blank')
  await captureStableShots({ x: ellipse.x + ellipse.width / 2, y: ellipse.y + ellipse.height / 2 }, 'usecase-center')
  await captureStableShots({ x: ellipse.x + 2, y: ellipse.y + ellipse.height / 2 }, 'usecase-edge')

  const usecaseCenter = { x: ellipse.x + ellipse.width / 2, y: ellipse.y + ellipse.height / 2 }
  await page.mouse.click(usecaseCenter.x, usecaseCenter.y)
  await expect(page.locator('.uc-detail')).toBeVisible()
  const clickPhase = await collectPhase(page, 'selected-usecase', usecaseCenter, 3000)
  expect(clickPhase.metrics.svgRect.uniqueCount).toBe(1)
  expect(clickPhase.metrics.viewBox.uniqueCount).toBe(1)
  expect(clickPhase.metrics.viewportRect.uniqueCount).toBe(1)

  const beforeDrag = await page.locator('.uc-hit').evaluateAll(nodes => nodes.map(node => {
    const box = node.getBBox()
    return [box.x, box.y, box.width, box.height]
  }))
  await page.mouse.move(usecaseCenter.x, usecaseCenter.y)
  await page.mouse.down()
  await page.mouse.move(usecaseCenter.x + 24, usecaseCenter.y + 12, { steps: 4 })
  await page.mouse.up()
  await page.waitForTimeout(100)
  const afterDrag = await page.locator('.uc-hit').evaluateAll(nodes => nodes.map(node => {
    const box = node.getBBox()
    return [box.x, box.y, box.width, box.height]
  }))
  expect(afterDrag[0][0]).not.toBe(beforeDrag[0][0])
  expect(afterDrag[0][1]).not.toBe(beforeDrag[0][1])
  expect(afterDrag.slice(1)).toEqual(beforeDrag.slice(1))
  const draggedPhase = await collectPhase(page, 'after-usecase-drag', { x: usecaseCenter.x + 24, y: usecaseCenter.y + 12 }, 3000)
  expect(draggedPhase.metrics.svgRect.uniqueCount).toBe(1)
  expect(draggedPhase.metrics.viewBox.uniqueCount).toBe(1)
  expect(draggedPhase.metrics.viewportRect.uniqueCount).toBe(1)
})
