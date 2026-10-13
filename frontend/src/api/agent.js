import { api } from './client'

export const agentApi = {
  config: () => api('/api/agent/config'),
  planning: {
    create: (request, projectId) => api('/api/planning-agent/runs', { method: 'POST', body: JSON.stringify({ request, project_id: projectId }) }),
    get: (id, projectId) => api(`/api/planning-agent/runs/${id}?projectId=${encodeURIComponent(projectId)}`),
    impact: (actions, projectId) => api('/api/planning-agent/impact', { method: 'POST', body: JSON.stringify({ actions, project_id: projectId }) }),
    confirm: (id, projectId) => api(`/api/planning-agent/runs/${id}/confirm?projectId=${encodeURIComponent(projectId)}`, { method: 'POST' }),
    cancel: (id, projectId) => api(`/api/planning-agent/runs/${id}/cancel?projectId=${encodeURIComponent(projectId)}`, { method: 'POST' })
  }
}
