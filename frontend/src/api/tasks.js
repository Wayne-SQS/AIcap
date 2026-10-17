import { api } from './client'

export const tasksApi = {
  list: () => api('/api/tasks'),
  create: payload => api('/api/tasks', { method: 'POST', body: JSON.stringify(payload) }),
  patch: (id, payload) => api('/api/tasks/' + id, { method: 'PATCH', body: JSON.stringify(payload) })
}
