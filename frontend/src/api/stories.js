import { api } from './client'

export const storiesApi = {
  list: () => api('/api/stories'),
  create: payload => api('/api/stories', { method: 'POST', body: JSON.stringify(payload) }),
  patch: (id, payload) => api('/api/stories/' + id, { method: 'PATCH', body: JSON.stringify(payload) }),
  remove: (id, undone) => api('/api/stories/' + id + '?undone=' + undone, { method: 'DELETE' }),
  logs: () => api('/api/stories/logs')
}
