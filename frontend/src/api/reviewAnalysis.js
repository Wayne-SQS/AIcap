import { api } from './client'

export async function analyzeReview(meetingId, payload) {
  const result = await api(`/api/meetings/${encodeURIComponent(meetingId)}/review/analyze`, {
    method: 'POST', body: JSON.stringify(payload)
  }, '/meeting-ai')
  if (!result || typeof result.analysis_id !== 'string' || !result.analysis_id
      || result.meeting_id !== meetingId || result.client_request_id !== payload.client_request_id
      || !['pending', 'no_changes'].includes(result.storage_status)) {
    throw new Error('保存确认无效，请使用同一请求重试')
  }
  return result
}
