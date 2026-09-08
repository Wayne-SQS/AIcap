import { api } from './client'

/* 建议(手动录入 / Agent 生成)与审核:client_request_id 幂等去重由调用方维护 */
export const suggestionsApi = {
  list: () => api('/api/suggestions'),
  create: payload => api('/api/suggestions', { method: 'POST', body: JSON.stringify(payload) }),
  review: (id, payload) => api(`/api/suggestions/${id}/review`, { method: 'POST', body: JSON.stringify(payload) })
}
