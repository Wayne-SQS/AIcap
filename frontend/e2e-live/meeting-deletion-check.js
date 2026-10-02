import { expect } from '@playwright/test'

export async function assertMeetingRetained(request, base, headers, meetingId, recordPath) {
  const paths = [`/api/meetings/${meetingId}`, recordPath, recordPath + '/proposal-reviews',
    recordPath + '/proposal-executions', '/api/stories', '/api/stories/logs', `/api/meetings/${meetingId}/action-items`]
  const read = async path => {
    const result = await request.get(base + path, { headers })
    expect(result.status()).toBe(200)
    return result.json()
  }
  const before = await Promise.all(paths.map(read))
  const response = await request.delete(base + `/api/meetings/${meetingId}`, { headers })
  expect(response.status()).toBe(409)
  expect((await response.json()).detail).toContain('保留原文及审核执行审计')
  expect(await Promise.all(paths.map(read))).toEqual(before)
}

export async function assertEmptyMeetingDeletable(request, base, headers) {
  const created = await request.post(base + '/api/meetings', { headers, data: { title: '无分析删除验证', transcript: '未发起分析。' } })
  expect(created.status()).toBe(201)
  const id = (await created.json()).id
  expect((await request.delete(base + `/api/meetings/${id}`, { headers })).status()).toBe(200)
  expect((await request.get(base + `/api/meetings/${id}`, { headers })).status()).toBe(404)
}
