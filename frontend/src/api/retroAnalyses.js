import { api } from './client'

const base = meetingId => `/api/meetings/${encodeURIComponent(meetingId)}`
const record = (meetingId, analysisId) => `${base(meetingId)}/retro-analyses/${encodeURIComponent(analysisId)}`
export const retroAnalysesApi = {
  list: id => api(`${base(id)}/retro-analyses`),
  reviews: (id, analysis) => api(`${record(id, analysis)}/proposal-reviews`),
  executions: (id, analysis) => api(`${record(id, analysis)}/proposal-executions`),
  review: (id, analysis, payload) => api(`${record(id, analysis)}/proposal-reviews`, { method: 'POST', body: JSON.stringify(payload) }),
  actions: id => api(`${base(id)}/action-items`),
  logs: (id, action) => api(`${base(id)}/action-items/${encodeURIComponent(action)}/logs`),
  async execute(id, analysis, proposal) {
    const result = await api(`${record(id, analysis)}/proposal-executions`, { method: 'POST', body: JSON.stringify({ proposal_id: proposal }) })
    const positive = value => Number.isSafeInteger(value) && value > 0
    if (!result || result.analysis_id !== analysis || result.proposal_id !== proposal
        || result.execution_status !== 'succeeded' || typeof result.action_item_id !== 'string' || !result.action_item_id.trim()
        || !positive(result.action_item_log_id) || !positive(result.executed_by)
        || typeof result.executed_at !== 'string' || !result.executed_at.trim()) {
      throw new Error('执行确认无效，请刷新处理状态核对或重试同一提案')
    }
    return result
  }
}
