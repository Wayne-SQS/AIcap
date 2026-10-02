import { api } from './client'

const base = meetingId => `/api/meetings/${encodeURIComponent(meetingId)}/refinement-analyses`
const record = (meetingId, analysisId) => `${base(meetingId)}/${encodeURIComponent(analysisId)}`

async function execute(meetingId, analysisId, proposalId) {
  const result = await api(`${record(meetingId, analysisId)}/proposal-executions`, {
    method: 'POST', body: JSON.stringify({ proposal_id: proposalId })
  })
  const positiveId = value => Number.isSafeInteger(value) && value > 0
  if (!result || Array.isArray(result)
      || result.analysis_id !== analysisId || result.proposal_id !== proposalId
      || result.execution_status !== 'succeeded'
      || typeof result.story_id !== 'string' || !/^US[0-9]+$/.test(result.story_id)
      || !positiveId(result.story_log_id) || !positiveId(result.executed_by)
      || typeof result.executed_at !== 'string' || !result.executed_at.trim()) {
    // A 2xx response is not proof of a persisted execution. Keep the same proposal
    // available for status refresh/idempotent retry; never cache partial success.
    throw new Error('执行确认无效，请刷新处理状态核对或重试同一提案')
  }
  return result
}

export const refinementAnalysesApi = {
  execute,
  review: (meetingId, analysisId, payload) => api(`${record(meetingId, analysisId)}/proposal-reviews`, { method: 'POST', body: JSON.stringify(payload) }),
  list: meetingId => api(base(meetingId)),
  reviews: (meetingId, analysisId) => api(`${record(meetingId, analysisId)}/proposal-reviews`),
  executions: (meetingId, analysisId) => api(`${record(meetingId, analysisId)}/proposal-executions`)
}
