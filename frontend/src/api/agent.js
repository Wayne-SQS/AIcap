import { api } from './client'

export const agentApi = {
  config: () => api('/api/agent/config'),
  planning: {
    create: request => api('/api/planning-agent/runs', { method: 'POST', body: JSON.stringify({ request }) }),
    get: id => api('/api/planning-agent/runs/' + id),
    impact: actions => api('/api/planning-agent/impact', { method: 'POST', body: JSON.stringify({ actions }) }),
    confirm: id => api(`/api/planning-agent/runs/${id}/confirm`, { method: 'POST' }),
    cancel: id => api(`/api/planning-agent/runs/${id}/cancel`, { method: 'POST' })
  }
}
