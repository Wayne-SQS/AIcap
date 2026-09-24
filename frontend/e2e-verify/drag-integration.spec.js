import { test, expect } from '@playwright/test'

async function openApp(page, hash = '/#/board') {
  await page.goto(hash)
  await expect(page.locator('.view')).toBeVisible()
}

async function pointerDrag(page, source, target) {
  await source.scrollIntoViewIfNeeded()
  const from = await source.evaluate(el => el.getBoundingClientRect().toJSON())
  const to = await target.evaluate(el => el.getBoundingClientRect().toJSON())
  const viewport = page.viewportSize()
  const targetY = Math.max(80, Math.min(viewport.height - 50, Math.max(to.top + 20, Math.min(to.bottom - 20, to.top + to.height / 2))))
  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2)
  await page.mouse.down()
  await page.mouse.move(to.x + to.width / 2, targetY, { steps: 12 })
  await page.mouse.up()
}

test('故事地图拖动会持久化，失败时回滚', async ({ page }) => {
  await openApp(page)
  const story = page.locator('#map .card[data-id="US09"]')
  await pointerDrag(page, story, page.locator('[data-map-drop="3-3"]'))
  await expect(page.locator('[data-map-drop="3-3"] .card[data-id="US09"]')).toBeVisible()
  await page.reload()
  await expect(page.locator('[data-map-drop="3-3"] .card[data-id="US09"]')).toBeVisible()
  await page.evaluate(() => {
    const story = window.stories.find(item => item.id === 'US09')
    story.sprint = 4
    story.activity = 3
    localStorage.setItem('aiguanli-pixel-stories-v1', JSON.stringify(window.stories))
  })
  await page.reload()
  await expect(page.locator('[data-map-drop="4-3"] .card[data-id="US09"]')).toBeVisible()

  await page.evaluate(() => {
    window.sessionStore.apiMode = true
    window.sessionStore.currentUser = { id: 1, role: 'admin', display_name: '测试管理员' }
  })
  await page.route('**/api/stories/US09', route => route.fulfill({ status: 500, body: JSON.stringify({ detail: 'mock failure' }), contentType: 'application/json' }))
  await pointerDrag(page, page.locator('#map .card[data-id="US09"]'), page.locator('[data-map-drop="3-3"]'))
  await expect(page.locator('[data-map-drop="4-3"] .card[data-id="US09"]')).toBeVisible()
  await expect(page.locator('#toast')).toContainText('更新失败')
})

test('甘特任务平移后周负载同步并可还原', async ({ page }) => {
  page.on('dialog', dialog => dialog.accept())
  await openApp(page, '/#/gantt')
  const bar = page.locator('.gbar[data-gantt-task="task:T05"]')
  await bar.scrollIntoViewIfNeeded()
  const before = await bar.evaluate(el => ({ left: el.style.left, width: el.style.width, rect: el.getBoundingClientRect().toJSON() }))
  await page.mouse.move(before.rect.x + before.rect.width / 2, before.rect.y + before.rect.height / 2)
  await page.mouse.down()
  await page.mouse.move(before.rect.x + before.rect.width * 1.5, before.rect.y + before.rect.height / 2, { steps: 8 })
  await page.mouse.up()
  await expect(bar).toHaveAttribute('style', /left: 33\.33%/)
  await page.reload()
  await expect(bar).toHaveAttribute('style', /left: 33\.33%/)
  expect(await page.evaluate(() => window.tasks.find(t => t.id === 'T05').w)).toEqual([3, 3])

  await page.goto('/#/members')
  expect(await page.evaluate(() => window.tasks.find(t => t.id === 'T05').w)).toEqual([3, 3])
  const p4w3 = page.locator('.heat-row').nth(3).locator('.heat-cell').nth(2)
  await expect(p4w3).toContainText('22')

  await page.goto('/#/gantt')
  await bar.scrollIntoViewIfNeeded()
  const moved = await bar.evaluate(el => el.getBoundingClientRect().toJSON())
  await page.mouse.move(moved.x + moved.width / 2, moved.y + moved.height / 2)
  await page.mouse.down()
  await page.mouse.move(moved.x - moved.width / 2, moved.y + moved.height / 2, { steps: 8 })
  await page.mouse.up()
  await expect(bar).toHaveAttribute('style', /left: 16\.67%/)
})

test('成员任务转派会更新 owner、统计和甘特颜色', async ({ page }) => {
  await openApp(page, '/#/members')
  await page.locator('.mcard.clickable[data-member="3"]').click()
  await page.locator('.task-item', { hasText: 'T05' }).dragTo(page.locator('[data-owner-drop="4"]'))
  await expect(page.locator('.task-item', { hasText: 'T05' })).toHaveCount(0)
  await page.locator('.drawer-close').click()
  await page.locator('.mcard.clickable[data-member="4"]').click()
  await expect(page.locator('.task-item', { hasText: 'T05' })).toBeVisible()
  await page.locator('.task-item', { hasText: 'T05' }).dragTo(page.locator('[data-owner-drop="3"]'))
  await expect(page.locator('.task-item', { hasText: 'T05' })).toHaveCount(0)
  await page.locator('.drawer-close').click()
  await page.goto('/#/gantt')
  await expect(page.locator('.gbar[data-gantt-task="task:T05"]')).toHaveClass(/o3/)
})

test('UML 拖动只保存布局，不修改业务数据', async ({ page }) => {
  await openApp(page, '/#/uml')
  const usecase = page.locator('.uc-hit').first()
  const beforeStories = await page.locator('.uc-detail').count()
  const box = await usecase.evaluate(el => el.getBoundingClientRect().toJSON())
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
  await page.mouse.down()
  await page.mouse.move(box.x + box.width / 2 + 25, box.y + box.height / 2 + 15, { steps: 5 })
  await page.mouse.up()
  const layout = await page.evaluate(() => localStorage.getItem('aiguanli-uml-layout-v1'))
  expect(layout).toContain('usecases')
  expect(await page.locator('.uc-detail').count()).toBe(beforeStories)

  const participant = page.locator('.participant-hit').nth(1).locator('.participant')
  await participant.scrollIntoViewIfNeeded()
  const participantBox = await participant.evaluate(el => el.getBoundingClientRect().toJSON())
  const beforeParticipantX = JSON.parse(await page.evaluate(() => localStorage.getItem('aiguanli-uml-layout-v1')))
    .participants.find(item => item.id === 'frontend').x
  // The participant header is the drag handle; the lifeline below it is intentionally passive.
  await page.mouse.move(participantBox.x + participantBox.width / 2, participantBox.y + 20)
  await page.mouse.down()
  await page.mouse.move(participantBox.x + participantBox.width / 2 + 20, participantBox.y + 20, { steps: 5 })
  await page.mouse.up()
  const saved = JSON.parse(await page.evaluate(() => localStorage.getItem('aiguanli-uml-layout-v1')))
  expect(saved.participants.find(item => item.id === 'frontend').x).toBeGreaterThan(beforeParticipantX)
})

test('viewer 看得到视图但没有业务拖拽入口', async ({ page }) => {
  await openApp(page, '/#/board')
  const setViewer = () => page.evaluate(() => {
    window.sessionStore.apiMode = true
    window.sessionStore.currentUser = { id: 5, role: 'viewer', display_name: '只读查看者' }
  })
  await setViewer()
  await expect(page.locator('#map .card').first()).toHaveAttribute('draggable', 'false')
  await page.goto('/#/gantt')
  await setViewer()
  await expect(page.locator('.gbar[data-gantt-task="task:T05"]')).not.toHaveClass(/is-draggable/)
  await page.goto('/#/members')
  await setViewer()
  await page.locator('.mcard.clickable[data-member="3"]').click()
  await expect(page.locator('.task-item').first()).toHaveAttribute('draggable', 'false')
})
