import { randomUUID } from 'node:crypto'
import { test, expect } from '@playwright/test'

// T996/T997 are temporary persisted rows in aicap_java_test, removed by the
// SQL cleanup after this scenario. They provide a pending predecessor and overload.
const javaBase = process.env.AICAP_LIVE_JAVA_BASE || 'http://127.0.0.1:8080'
const aiBase = process.env.AICAP_LIVE_AI_BASE || 'http://127.0.0.1:8090'
const projectId = 'P002'

test('Sprint 2 Assignment explains task conflicts and executes only after separate review', async ({ request }) => {
  const login = await request.post(`${javaBase}/api/auth/login`, {
    data: { username: '李锐铭', password: '123456' }
  })
  expect(login.status()).toBe(200)
  const headers = { Authorization: `Bearer ${(await login.json()).access_token}` }
  const story = await request.post(`${javaBase}/api/stories?projectId=${projectId}`, {
    headers, data: { title: 'Sprint2分配门禁联调', description: '核对任务依赖与成员容量',
      role: '负责人', goal: '安全分配', acceptance: '审核与执行分离并保留记录',
      priority: 'Should', sprint: 2, activity: 3, status: 0, owner_id: 1 }
  })
  expect(story.status()).toBe(200)
  const storyId = (await story.json()).id
  const meeting = await request.post(`${javaBase}/api/meetings?projectId=${projectId}`, {
    headers, data: { project_id: projectId, title: `Sprint2分配联调 ${randomUUID().slice(0, 8)}`,
      transcript: `${storyId} 的技能和容量由负责人核对，阻塞任务先处理。` }
  })
  expect(meeting.status()).toBe(201)
  const meetingId = (await meeting.json()).id
  const input = { story_ids: [storyId], target_sprint: 2,
    requirements: [{ dimension: 'tech_stack', name: 'Python', minimum_level: 3 }] }
  const aiPath = `${aiBase}/api/meetings/${meetingId}/assignment`
  const javaPath = `${javaBase}/api/meetings/${meetingId}/assignment-suggestions`
  const readStory = async () => {
    const response = await request.get(`${javaBase}/api/stories?projectId=${projectId}`, { headers })
    expect(response.status()).toBe(200)
    return (await response.json()).find(row => row.id === storyId)
  }
  try {
    const context = await request.post(`${aiPath}/context?projectId=${projectId}`, {
      headers, data: { story_ids: [storyId], target_sprint: 2 }
    })
    expect(context.status()).toBe(200)
    const facts = await context.json()
    expect(facts.tasks.some(row => row.id === 'T997')).toBe(true)
    const gapCodes = new Set(facts.gaps.map(row => row.code))
    for (const code of ['task_blocked', 'task_dependency_pending', 'sprint_capacity_overloaded', 'story_estimates_unavailable']) {
      expect(gapCodes.has(code), `missing ${code}`).toBe(true)
    }
    const overloaded = facts.members.find(row => row.profile.user_id === 1)
    expect(overloaded.sprint_remaining_hours).toBeLessThan(0)

    const recommended = await request.post(`${aiPath}/recommendations?projectId=${projectId}`, { headers, data: input })
    expect(recommended.status()).toBe(200)
    const preview = await recommended.json()
    expect(preview.writes_performed).toBe(false)
    expect(preview.excluded_viewer_ids).toContain(5)
    expect(preview.candidates.find(row => row.member_id === 2)).toMatchObject({
      capacity_check: 'unknown', suitability: 'requires_human_review'
    })
    expect((await readStory()).owner_id).toBe(1)

    const save = async () => {
      const response = await request.post(`${aiPath}/suggestions?projectId=${projectId}`, {
        headers, data: { ...input, client_request_id: randomUUID() }
      })
      expect(response.status()).toBe(200)
      return response.json()
    }
    const first = await save()
    expect(first.review).toBeNull()
    expect((await request.post(`${javaPath}/${first.id}/execute`, { headers, data: {} })).status()).toBe(409)
    expect((await readStory()).owner_id).toBe(1)
    const approved = await request.post(`${javaPath}/${first.id}/review`, {
      headers, data: { decision: 'approve', member_id: 2,
        reason: '已逐项核对阻塞与容量，先调整故事负责人', capacity_acknowledged: true }
    })
    expect(approved.status()).toBe(200)
    expect((await readStory()).owner_id).toBe(1)
    const executed = await request.post(`${javaPath}/${first.id}/execute`, { headers, data: {} })
    expect(executed.status()).toBe(200)
    const firstExecution = await executed.json()
    expect(firstExecution).toMatchObject({ previous_owner_id: 1, new_owner_id: 2, execution_status: 'succeeded' })
    expect((await readStory()).owner_id).toBe(2)
    const repeated = await request.post(`${javaPath}/${first.id}/execute`, { headers, data: {} })
    expect(await repeated.json()).toEqual(firstExecution)

    const second = await save()
    const review = await request.post(`${javaPath}/${second.id}/review`, {
      headers, data: { decision: 'approve', member_id: 1,
        reason: '验证过期快照失败后可在恢复原状态时重试', capacity_acknowledged: true }
    })
    expect(review.status()).toBe(200)
    const statusChanged = await request.patch(`${javaBase}/api/stories/${storyId}?projectId=${projectId}`, {
      headers, data: { status: 1 }
    })
    expect(statusChanged.status()).toBe(200)
    expect((await request.post(`${javaPath}/${second.id}/execute`, { headers, data: {} })).status()).toBe(409)
    expect((await readStory()).owner_id).toBe(2)
    const pending = await request.get(`${javaPath}/${second.id}`, { headers })
    expect((await pending.json()).review.execution_status).toBe('not_started')
    const restored = await request.patch(`${javaBase}/api/stories/${storyId}?projectId=${projectId}`, {
      headers, data: { status: 0 }
    })
    expect(restored.status()).toBe(200)
    const retried = await request.post(`${javaPath}/${second.id}/execute`, { headers, data: {} })
    expect(retried.status()).toBe(200)
    expect((await readStory()).owner_id).toBe(1)
  } finally {
    const removed = await request.delete(`${javaBase}/api/stories/${storyId}?projectId=${projectId}&undone=keep`, { headers })
    expect(removed.status()).toBe(200)
  }
})
