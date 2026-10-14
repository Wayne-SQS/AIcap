import { api } from './client'

const base = meetingId => `/api/meetings/${encodeURIComponent(meetingId)}/refinement-analyses`
const record = (meetingId, analysisId) => `${base(meetingId)}/${encodeURIComponent(analysisId)}`
// 审核与执行都是写操作，服务端要项目上下文（projectId 必填）：故事编号是"项目内唯一"，
// 没有它就会 422。其余分析类的列表接口不受影响。
const scoped = (path, projectId) => `${path}?projectId=${encodeURIComponent(projectId || '')}`

async function execute(meetingId, analysisId, proposalId, projectId) {
  const result = await api(scoped(`${record(meetingId, analysisId)}/proposal-executions`, projectId), {
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
  review: (meetingId, analysisId, payload, projectId) =>
    api(scoped(`${record(meetingId, analysisId)}/proposal-reviews`, projectId), { method: 'POST', body: JSON.stringify(payload) }),
  list: meetingId => api(base(meetingId)),
  reviews: (meetingId, analysisId, projectId) => api(scoped(`${record(meetingId, analysisId)}/proposal-reviews`, projectId)),
  executions: (meetingId, analysisId, projectId) => api(scoped(`${record(meetingId, analysisId)}/proposal-executions`, projectId))
}
