import { randomUUID } from 'node:crypto'
import { test, expect } from '@playwright/test'

const projectId = 'P002'
const javaBase = process.env.AICAP_LIVE_JAVA_BASE || 'http://127.0.0.1:18180'

async function enterProject(browser, username) {
  const context = await browser.newContext()
  const page = await context.newPage()
  await page.goto('/#/projects')
  await page.locator('#login-user').fill(username)
  await page.locator('#login-pass').fill('123456')
  await page.locator('#login-form').getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.locator('#login')).not.toBeVisible()
  await page.getByRole('button', { name: '进入项目' }).click()
  await expect(page).toHaveURL(new RegExp(`/projects/${projectId}/overview`))
  await page.goto(`/#/projects/${projectId}/review`)
  return { context, page }
}

test('Sprint 2 review queue lets a viewer inspect a proposal but only a reviewer reject it', async ({ browser, request }) => {
  const login = await request.post(javaBase + '/api/auth/login', {
    data: { username: '李锐铭', password: '123456' }
  })
  expect(login.status()).toBe(200)
  const headers = { Authorization: `Bearer ${(await login.json()).access_token}` }
  const transcript = '联调会议确认需要先人工核对 Sprint 2 建议，再决定是否采纳。'
  const meeting = await request.post(`${javaBase}/api/meetings?projectId=${projectId}`, {
    headers, data: { project_id: projectId, title: `Sprint 2 审核联调 ${randomUUID().slice(0, 8)}`, transcript }
  })
  expect(meeting.status()).toBe(201)
  const meetingId = (await meeting.json()).id
  try {
    const suggestion = await request.post(`${javaBase}/api/suggestions?projectId=${projectId}`, {
      headers, data: {
        meeting_id: meetingId, client_request_id: randomUUID(), action: 'pool.create', origin: 'manual',
        evidence: '先人工核对 Sprint 2 建议',
        changes: { title: 'Sprint 2 审核联调建议', priority: 'Should' }
      }
    })
    expect(suggestion.status()).toBe(200)
    const suggestionId = (await suggestion.json()).id

    const viewer = await enterProject(browser, '成员5')
    try {
      const queue = viewer.page.getByRole('region', { name: '跨流程提案队列' })
      const row = queue.locator(`[data-meeting-suggestion="${suggestionId}"]`)
      await expect(row).toBeVisible()
      await expect(row).toContainText('待审核')
      await expect(queue.locator('.queue-permission')).toBeVisible()
      await expect(row.locator('.sug-acts')).toHaveCount(0)
      await row.getByRole('button', { name: '查看来源会议' }).click()
      await expect(viewer.page).toHaveURL(new RegExp(`/projects/${projectId}/meetings`))
    } finally { await viewer.context.close() }

    const admin = await enterProject(browser, '李锐铭')
    try {
      const row = admin.page.locator(`[data-meeting-suggestion="${suggestionId}"]`)
      await expect(row).toBeVisible()
      admin.page.once('dialog', dialog => dialog.accept('浏览器联调：证据尚不足'))
      await row.getByRole('button', { name: '拒绝', exact: true }).click()
      await expect(row).toHaveCount(0)
    } finally { await admin.context.close() }

    const saved = await request.get(`${javaBase}/api/suggestions/${suggestionId}?projectId=${projectId}`, { headers })
    expect(saved.status()).toBe(200)
    expect(await saved.json()).toMatchObject({ status: 'rejected', pool_item_id: null })
  } finally {
    const removed = await request.delete(`${javaBase}/api/meetings/${meetingId}?projectId=${projectId}`, { headers })
    expect(removed.status()).toBe(200)
  }
})

test('Sprint 2 modified approval creates one pool item and leaves formal stories unchanged', async ({ browser, request }) => {
  const login = await request.post(javaBase + '/api/auth/login', {
    data: { username: '李锐铭', password: '123456' }
  })
  expect(login.status()).toBe(200)
  const headers = { Authorization: `Bearer ${(await login.json()).access_token}` }
  const transcript = '评审联调同意把需求建议先送入需求池，由负责人再决定是否转成故事。'
  const meeting = await request.post(`${javaBase}/api/meetings?projectId=${projectId}`, {
    headers, data: { project_id: projectId, title: `Sprint 2 修改采纳联调 ${randomUUID().slice(0, 8)}`, transcript }
  })
  expect(meeting.status()).toBe(201)
  const meetingId = (await meeting.json()).id
  let poolId = null
  try {
    const beforeStories = await request.get(`${javaBase}/api/stories?projectId=${projectId}`, { headers })
    expect(beforeStories.status()).toBe(200)
    const initialStoryCount = (await beforeStories.json()).length
    const suggestion = await request.post(`${javaBase}/api/suggestions?projectId=${projectId}`, {
      headers, data: {
        meeting_id: meetingId, client_request_id: randomUUID(), action: 'pool.create', origin: 'manual',
        evidence: '需求建议先送入需求池',
        changes: { title: '原始联调建议', description: '需要人工核对', priority: 'Could' }
      }
    })
    expect(suggestion.status()).toBe(200)
    const suggestionId = (await suggestion.json()).id
    const admin = await enterProject(browser, '李锐铭')
    try {
      const row = admin.page.locator(`[data-meeting-suggestion="${suggestionId}"]`)
      await expect(row).toBeVisible()
      await row.getByRole('button', { name: '修改后采纳' }).click()
      const dialog = admin.page.getByRole('dialog')
      await dialog.locator('input[name="title"]').fill('修改后联调需求')
      await dialog.locator('textarea[name="reason"]').fill('保留到需求池待精炼')
      await dialog.getByRole('button', { name: '确认' }).click()
      await expect(row).toHaveCount(0)
    } finally { await admin.context.close() }

    const saved = await request.get(`${javaBase}/api/suggestions/${suggestionId}?projectId=${projectId}`, { headers })
    expect(saved.status()).toBe(200)
    const reviewed = await saved.json()
    expect(reviewed).toMatchObject({ status: 'approved', reason: '保留到需求池待精炼' })
    poolId = reviewed.pool_item_id
    expect(poolId).toBeTruthy()
    const pool = await request.get(`${javaBase}/api/pool?projectId=${projectId}`, { headers })
    expect(pool.status()).toBe(200)
    expect((await pool.json()).filter(row => row.id === poolId)).toHaveLength(1)
    const stories = await request.get(`${javaBase}/api/stories?projectId=${projectId}`, { headers })
    expect(stories.status()).toBe(200)
    expect((await stories.json()).length).toBe(initialStoryCount)
  } finally {
    if (poolId) {
      const removedPool = await request.delete(`${javaBase}/api/pool/${poolId}?projectId=${projectId}`, { headers })
      expect(removedPool.status()).toBe(200)
    }
    const removedMeeting = await request.delete(`${javaBase}/api/meetings/${meetingId}?projectId=${projectId}`, { headers })
    expect(removedMeeting.status()).toBe(200)
  }
})
