import { api } from './client'
export const projectsApi = {
  list: () => api('/api/projects'),
  get: id => api('/api/projects/' + encodeURIComponent(id)),
  members: id => api('/api/projects/' + encodeURIComponent(id) + '/members'),
  updateMemberCapacity: (id, userId, payload) => api('/api/projects/' + encodeURIComponent(id) + '/members/' + encodeURIComponent(userId) + '/capacity', { method: 'PATCH', body: JSON.stringify(payload) }),
  create: payload => api('/api/projects', { method: 'POST', body: JSON.stringify(payload) })
}
