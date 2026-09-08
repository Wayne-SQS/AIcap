import { api } from './client'

export const tasksApi = {
  list: () => api('/api/tasks'),
  patch: (id, payload) => api('/api/tasks/' + id, { method: 'PATCH', body: JSON.stringify(payload) })
}
