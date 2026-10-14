import { test, expect } from '@playwright/test'

async function openOfflineUml(page) {
  await page.route('**/api/health', route => route.fulfill({ status: 503, body: '{}' }))
  await page.goto('/#/uml')
  await expect(page.locator('.usecase-svg .uc-hit').first()).toBeVisible()
}

async function position(node) {
  return node.locator('ellipse').evaluate(ellipse => [
    Number(ellipse.getAttribute('cx')), Number(ellipse.getAttribute('cy'))
  ])
}

async function dragNode(page, node) {
  await node.scrollIntoViewIfNeeded()
  const box = await node.boundingBox()
  const x = box.x + box.width / 2
  const y = box.y + box.height / 2
  await page.mouse.move(x, y)
  await page.mouse.down()
  await page.mouse.move(x + 35, y + 18, { steps: 5 })
  await page.mouse.up()
}

test('故事叙述变更重算 UML 关联，同时保留人工调整的布局', async ({ page }) => {
  await openOfflineUml(page)
  const first = page.locator('.usecase-svg .uc-hit').first()
  const workload = page.locator('.usecase-svg .uc-hit').filter({ hasText: '分析成员工作量' })
  const count = async () => Number((await workload.getAttribute('aria-label')).match(/关联 (\d+) 条故事/)?.[1])
  const beforeCount = await count()
  const before = await position(first)
  await dragNode(page, first)
  const moved = await position(first)
  expect(moved).not.toEqual(before)

  await page.evaluate(() => {
    const keywords = ['成员任务图', '成员开发活动图', '过载']
    const story = window.stories.find(item => !keywords.some(keyword =>
      [item.title, item.activity, item.description, item.role, item.goal, item.value, item.acceptance]
        .filter(Boolean).join(' ').includes(keyword)))
    if (!story) throw new Error('缺少可用于验证关联更新的故事')
    story.goal = `${story.goal || ''} 成员开发活动图`
  })

  await expect.poll(count).toBe(beforeCount + 1)
  expect(await position(first)).toEqual(moved)
})

test('UML 布局按项目保存，切换项目后可恢复各自的位置', async ({ page }) => {
  await openOfflineUml(page)
  await page.evaluate(() => localStorage.setItem('aiguanli-current-project', 'P101'))
  await page.reload()
  const first = page.locator('.usecase-svg .uc-hit').first()
  await expect(first).toBeVisible()
  const original = await position(first)
  await dragNode(page, first)
  const moved = await position(first)
  expect(moved).not.toEqual(original)
  expect(await page.evaluate(() => localStorage.getItem('aiguanli-uml-layout-v3-P101'))).toBeTruthy()

  await page.evaluate(() => localStorage.setItem('aiguanli-current-project', 'P102'))
  await page.reload()
  await expect(first).toBeVisible()
  expect(await position(first)).toEqual(original)

  await page.evaluate(() => localStorage.setItem('aiguanli-current-project', 'P101'))
  await page.reload()
  await expect(first).toBeVisible()
  expect(await position(first)).toEqual(moved)
  await first.click()
  await expect(page.locator('.uc-detail')).toContainText('来源：当前项目 P101 的正式故事')
})
