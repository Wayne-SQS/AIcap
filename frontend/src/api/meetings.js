import { api } from './client'

/* 会议与 Agent 运行:契约对齐 backend/app/routers/meetings.py + agent.py */
export const meetingsApi = {
  list: () => api('/api/meetings'),
  create: payload => api('/api/meetings', { method: 'POST', body: JSON.stringify(payload) }),
  get: id => api('/api/meetings/' + id),
  runs: id => api(`/api/meetings/${id}/runs`),
  startRun: id => api(`/api/meetings/${id}/runs`, { method: 'POST' }),
  getRun: runId => api('/api/agent-runs/' + runId),
  retryRun: runId => api(`/api/agent-runs/${runId}/retry`, { method: 'POST' })
}
