import { api } from './client'

/* 会议与 Agent 运行:契约对齐 backend/app/routers/meetings.py + agent.py */
export const meetingsApi = {
  list: projectId => api('/api/meetings?projectId=' + encodeURIComponent(projectId || '')),
  create: (payload, projectId) => api('/api/meetings', { method: 'POST', body: JSON.stringify({ ...payload, project_id: projectId }) }),
  get: (id, projectId) => api(`/api/meetings/${id}?projectId=${encodeURIComponent(projectId || '')}`),
  /* 删除会议(admin/owner):后端级联删除录音与历史建议记录,已批准产生的需求池条目不回滚 */
  remove: (id, projectId) => api(`/api/meetings/${id}?projectId=${encodeURIComponent(projectId || '')}`, { method: 'DELETE' }),
  runs: (id, projectId) => api(`/api/meetings/${id}/runs?projectId=${encodeURIComponent(projectId || '')}`),
  startRun: (id, projectId) => api(`/api/meetings/${id}/runs?projectId=${encodeURIComponent(projectId || '')}`, { method: 'POST' }),
  getRun: (runId, projectId) => api(`/api/agent-runs/${runId}?projectId=${encodeURIComponent(projectId || '')}`),
  retryRun: (runId, projectId) => api(`/api/agent-runs/${runId}/retry?projectId=${encodeURIComponent(projectId || '')}`, { method: 'POST' }),
  editMinutes: (runId, projectId, edits) => api(`/api/agent-runs/${runId}/minutes?projectId=${encodeURIComponent(projectId || '')}`, {
    method: 'PATCH', body: JSON.stringify(edits)
  })
}
