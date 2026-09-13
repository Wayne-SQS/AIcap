import { api } from './client'

/* 会议与 Agent 运行:契约对齐 backend/app/routers/meetings.py + agent.py */
export const meetingsApi = {
  list: () => api('/api/meetings'),
  create: payload => api('/api/meetings', { method: 'POST', body: JSON.stringify(payload) }),
  get: id => api('/api/meetings/' + id),
  /* 删除会议(admin/owner):后端级联删除录音与历史建议记录,已批准产生的需求池条目不回滚 */
  remove: id => api('/api/meetings/' + id, { method: 'DELETE' }),
  runs: id => api(`/api/meetings/${id}/runs`),
  startRun: id => api(`/api/meetings/${id}/runs`, { method: 'POST' }),
  getRun: runId => api('/api/agent-runs/' + runId),
  retryRun: runId => api(`/api/agent-runs/${runId}/retry`, { method: 'POST' })
}
