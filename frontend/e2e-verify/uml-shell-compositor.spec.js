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

async function hashes(locator, count = 5) {
  const result = []
  for (let index = 0; index < count; index++) {
    result.push(crypto.createHash('sha256').update(await locator.screenshot()).digest('hex'))
    await locator.page().waitForTimeout(350)
  }
  return result
}

async function applyExperiment(page, css) {
  await page.evaluate(cssText => {
    document.querySelector('#uml-shell-experiment')?.remove()
    const style = document.createElement('style')
    style.id = 'uml-shell-experiment'
    style.textContent = cssText
    document.head.appendChild(style)
  }, css)
  await page.waitForTimeout(500)
}

test('compare UML shells and exercise compositor A/B variants', async ({ page }) => {
  await login(page)
  await page.setViewportSize({ width: 2048, height: 1200 })
  await page.goto('/#/uml')
  await expect(page.locator('.usecase-svg')).toBeVisible()
  await expect(page.locator('.sequence-svg')).toBeVisible()
  await page.waitForTimeout(2000)

  const diagnostics = await page.evaluate(() => {
    const rect = node => {
      const value = node.getBoundingClientRect()
      return Object.fromEntries(['x', 'y', 'width', 'height', 'top', 'left', 'right', 'bottom'].map(key => [key, value[key]]))
    }
    const describe = node => {
      const style = getComputedStyle(node)
      return {
        tag: node.tagName,
        className: typeof node.className === 'string' ? node.className : node.className?.baseVal,
        rect: rect(node),
        client: [node.clientWidth, node.clientHeight],
        scroll: [node.scrollWidth, node.scrollHeight, node.scrollLeft, node.scrollTop],
        offset: [node.offsetWidth, node.offsetHeight, node.offsetLeft, node.offsetTop],
        computed: Object.fromEntries([
          'width', 'height', 'display', 'position', 'overflow', 'overflowX', 'overflowY',
          'transform', 'transformOrigin', 'contain', 'willChange', 'backfaceVisibility',
          'isolation', 'opacity', 'zIndex', 'boxSizing', 'padding', 'borderWidth'
        ].map(key => [key, style[key]])),
        inline: node.getAttribute('style') || '',
        viewBox: node.getAttribute?.('viewBox'),
        preserveAspectRatio: node.getAttribute?.('preserveAspectRatio') || '(default xMidYMid meet)'
      }
    }
    const chain = selector => {
      const nodes = []
      let node = document.querySelector(selector)
      while (node && nodes.length < 9) {
        nodes.push(describe(node))
        node = node.parentElement
      }
      return nodes
    }
    const firstFractional = items => items.find(item => Object.values(item.rect).some(value => Math.abs(value - Math.round(value)) > 0.0001)) || null
    const usecase = chain('.usecase-svg')
    const sequence = chain('.sequence-svg')
    return {
      dpr: window.devicePixelRatio,
      browserZoom: window.outerWidth ? window.innerWidth / window.outerWidth : null,
      screen: { width: screen.width, height: screen.height, availWidth: screen.availWidth, availHeight: screen.availHeight },
      usecase,
      sequence,
      usecaseFirstFractional: firstFractional(usecase),
      sequenceFirstFractional: firstFractional(sequence)
    }
  })
  console.log('[uml-shell:diagnostics]', JSON.stringify(diagnostics))

  const variants = [
    ['baseline', ''],
    ['transform-none', '.usecase-svg{transform:none!important;will-change:auto!important}'],
    ['integer-size', '.usecase-svg{width:1200px!important;height:680px!important;min-width:0!important;transform:none!important}.uml-canvas:not(.sequence-canvas)>.canvas-zoom-shell{width:1200px!important;height:680px!important;min-width:1200px!important;min-height:680px!important;padding:0!important}'],
    ['overflow-visible', '.uml-canvas:not(.sequence-canvas){overflow:visible!important}.usecase-svg{width:1200px!important;height:680px!important;min-width:0!important;transform:none!important}.uml-canvas:not(.sequence-canvas)>.canvas-zoom-shell{width:1200px!important;height:680px!important;min-width:1200px!important;min-height:680px!important;padding:0!important}'],
    ['contain-paint', '.uml-canvas:not(.sequence-canvas){contain:paint!important}'],
    ['contain-layout-paint', '.uml-canvas:not(.sequence-canvas){contain:layout paint!important}'],
    ['translate-z', '.usecase-svg{transform:translateZ(0)!important;backface-visibility:hidden!important;will-change:transform!important}'],
    ['integer-css-scale', '.usecase-svg{width:1200px!important;height:680px!important;min-width:0!important}.uml-canvas:not(.sequence-canvas)>.canvas-zoom-shell{width:1200px!important;min-width:1200px!important}']
  ]
  const results = []
  for (const [name, css] of variants) {
    await applyExperiment(page, css)
    const usecase = page.locator('.usecase-svg')
    const geometry = await usecase.evaluate(node => {
      const rect = node.getBoundingClientRect()
      const style = getComputedStyle(node)
      return {
        rect: [rect.x, rect.y, rect.width, rect.height],
        transform: style.transform,
        overflow: getComputedStyle(node.closest('.uml-canvas')).overflow,
        contain: getComputedStyle(node.closest('.uml-canvas')).contain,
        shell: (() => { const value = node.parentElement.getBoundingClientRect(); return [value.x, value.y, value.width, value.height] })()
      }
    })
    const shots = await hashes(usecase)
    results.push({ name, geometry, uniqueHashes: new Set(shots).size, hashes: shots })
  }
  console.log('[uml-shell:variants]', JSON.stringify(results))

  await applyExperiment(page, '')
  const swap = await page.evaluate(async () => {
    const usecase = document.querySelector('.usecase-svg')
    const sequence = document.querySelector('.sequence-svg')
    const usecaseShell = usecase.parentElement
    const sequenceShell = sequence.parentElement
    const usecaseNext = usecase.nextSibling
    const sequenceNext = sequence.nextSibling
    sequenceShell.insertBefore(usecase, sequenceNext)
    usecaseShell.insertBefore(sequence, usecaseNext)
    await new Promise(resolve => setTimeout(resolve, 500))
    const capture = node => {
      const rect = node.getBoundingClientRect()
      return [rect.x, rect.y, rect.width, rect.height, getComputedStyle(node).transform]
    }
    window.__umlShellSwap = { usecase, sequence, usecaseShell, sequenceShell, usecaseNext, sequenceNext }
    return { usecaseInSequence: capture(usecase), sequenceInUsecase: capture(sequence) }
  })
  const swappedUsecaseHashes = await hashes(page.locator('.usecase-svg'), 8)
  const swappedSequenceHashes = await hashes(page.locator('.sequence-svg'), 8)
  await page.evaluate(() => {
    const { usecase, sequence, usecaseShell, sequenceShell, usecaseNext, sequenceNext } = window.__umlShellSwap
    usecaseShell.insertBefore(usecase, usecaseNext)
    sequenceShell.insertBefore(sequence, sequenceNext)
    delete window.__umlShellSwap
  })
  console.log('[uml-shell:swap]', JSON.stringify({
    ...swap,
    usecaseInSequenceUniqueImages: new Set(swappedUsecaseHashes).size,
    sequenceInUsecaseUniqueImages: new Set(swappedSequenceHashes).size,
    usecaseImages: swappedUsecaseHashes,
    sequenceImages: swappedSequenceHashes
  }))

  expect(diagnostics.usecase.length).toBeGreaterThan(3)
  expect(diagnostics.sequence.length).toBeGreaterThan(3)
  expect(results).toHaveLength(8)
})
