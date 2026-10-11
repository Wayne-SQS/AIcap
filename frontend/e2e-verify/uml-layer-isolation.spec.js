import { test, expect } from '@playwright/test'
import crypto from 'node:crypto'

async function login(page) {
  await page.goto('/#/members')
  await expect(page.locator('#login')).toBeVisible()
  await page.locator('#login-user').fill('李锐铭')
  await page.locator('#login-pass').fill('123456')
  await page.locator('#login-form button[type=submit]').click()
  await expect(page.locator('#login')).toBeHidden()
}

async function phase(page, name, css) {
  await page.evaluate(({ name, css }) => {
    document.querySelector('#uml-layer-isolation')?.remove()
    const style = document.createElement('style')
    style.id = 'uml-layer-isolation'
    style.textContent = `.usecase-svg ${css}`
    document.head.appendChild(style)
    window.__umlLayer = name
  }, { name, css })
  await page.waitForTimeout(600)
  const hashes = []
  for (let i = 0; i < 6; i++) {
    hashes.push(crypto.createHash('sha256').update(await page.locator('.usecase-svg').screenshot()).digest('hex'))
    await page.waitForTimeout(500)
  }
  console.log(`[uml-layer:${name}]`, hashes)
  return hashes
}

test('UML use case layer isolation and coordinate diagnostics', async ({ page }) => {
  await login(page)
  await page.setViewportSize({ width: 2048, height: 1200 })
  await page.goto('/#/uml')
  await expect(page.locator('.usecase-svg')).toBeVisible()
  await page.waitForTimeout(2000)

  const diagnostics = await page.locator('.usecase-svg').evaluate(svg => ({
    dpr: window.devicePixelRatio,
    zoom: getComputedStyle(svg).zoom,
    transforms: [...svg.querySelectorAll('[transform]')].map(node => node.getAttribute('transform')),
    ellipses: [...svg.querySelectorAll('.usecase')].map(node => ['cx', 'cy', 'rx', 'ry'].map(name => node.getAttribute(name))),
    actors: [...svg.querySelectorAll('.actor-head')].map(node => ['cx', 'cy', 'r'].map(name => node.getAttribute(name))),
    lines: [...svg.querySelectorAll('.association')].map(node => node.getAttribute('d')),
    usecaseText: [...svg.querySelectorAll('.usecase-label')].map(node => [node.getAttribute('x'), node.getAttribute('y')]),
    foreignObjects: svg.querySelectorAll('foreignObject').length,
    pseudoRules: [...document.styleSheets].flatMap(sheet => { try { return [...sheet.cssRules] } catch { return [] } }).filter(rule => rule.selectorText?.includes(':hover')).map(rule => rule.selectorText)
  }))
  console.log('[uml-layer:diagnostics]', JSON.stringify(diagnostics))

  const phases = [
    ['A-boundary', '.uc-hit,.actor-hit,.association,.system-title,.legend-object{display:none!important}'],
    ['B-ellipse', '.uc-hit text,.actor-hit,.association,.system-title,.legend-object{display:none!important}'],
    ['C-usecase-text', '.actor-hit,.association,.system-title,.legend-object{display:none!important}'],
    ['D-actor-shapes', '.actor-label-object,.association,.system-title,.legend-object{display:none!important}'],
    ['E-actor-text', '.association,.system-title,.legend-object{display:none!important}'],
    ['F-lines', '.system-title,.legend-object{display:none!important}'],
    ['G-no-hover', '.uc-hit,.actor-hit{pointer-events:none!important}.association{pointer-events:none!important}'],
    ['H-no-hit-layer', '.uc-hit,.actor-hit{pointer-events:none!important}'
    ]
  ]
  const results = []
  for (const [name, css] of phases) results.push({ name, hashes: await phase(page, name, css) })
  console.log('[uml-layer:summary]', JSON.stringify(results))
  expect(results).toHaveLength(8)
})
