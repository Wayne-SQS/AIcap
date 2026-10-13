import { api } from './client'

/* 建议(手动录入 / Agent 生成)与审核:client_request_id 幂等去重由调用方维护 */
export const suggestionsApi = {
  list: projectId => api(`/api/suggestions?projectId=${encodeURIComponent(projectId || '')}`),
  create: (payload, projectId) => api(`/api/suggestions?projectId=${encodeURIComponent(projectId || '')}`, { method: 'POST', body: JSON.stringify(payload) }),
  get: (id, projectId) => api(`/api/suggestions/${id}?projectId=${encodeURIComponent(projectId || '')}`),
  review: (id, payload, projectId) => api(`/api/suggestions/${id}/review?projectId=${encodeURIComponent(projectId || '')}`, { method: 'POST', body: JSON.stringify(payload) })
}
