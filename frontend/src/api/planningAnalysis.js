import { api } from './client'

const intentPath = meetingId => `/api/meetings/${encodeURIComponent(meetingId)}/planning-analyses/save-intent`
export const planningSaveIntentApi = {
  async get(meetingId) {
    const result = await api(intentPath(meetingId))
    if (!result || !Object.hasOwn(result, 'intent')) throw new Error('待恢复请求格式无效')
    return result.intent
  },
  async put(meetingId, payload) {
    const result = await api(intentPath(meetingId), { method: 'PUT', body: JSON.stringify(payload) })
    const saved = result?.intent
    if (!saved || saved.client_request_id !== payload.client_request_id
        || saved.meeting_type !== payload.meeting_type || saved.current_sprint !== payload.current_sprint
        || saved.target_sprint !== payload.target_sprint) throw new Error('保存意图确认与原请求不一致')
    return saved
  },
  async clear(meetingId, key) {
    const result = await api(`${intentPath(meetingId)}?client_request_id=${encodeURIComponent(key)}`, { method: 'DELETE' })
    if (result?.cleared !== true) throw new Error('待恢复请求清理未确认')
  }
}

export async function analyzePlanning(meetingId, payload) {
  const result = await api(`/api/meetings/${encodeURIComponent(meetingId)}/planning/analyze`, {
    method: 'POST', body: JSON.stringify(payload)
  }, '/meeting-ai')
  if (!result || typeof result.analysis_id !== 'string' || !result.analysis_id
      || result.meeting_id !== meetingId || result.client_request_id !== payload.client_request_id
      || !['pending', 'no_changes'].includes(result.storage_status)) {
    throw new Error('保存确认无效，请使用同一请求重试')
  }
  return result
}
