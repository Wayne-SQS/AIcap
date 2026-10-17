import { api } from './client'
export const projectsApi = {
  list: () => api('/api/projects'),
  get: id => api('/api/projects/' + encodeURIComponent(id)),
  members: id => api('/api/projects/' + encodeURIComponent(id) + '/members'),
  updateMember: (id, userId, payload) => api('/api/projects/' + encodeURIComponent(id) + '/members/' + encodeURIComponent(userId), { method: 'PUT', body: JSON.stringify(payload) }),
  update: (id, payload) => api('/api/projects/' + encodeURIComponent(id), { method: 'PATCH', body: JSON.stringify(payload) }),
  removeMember: (id, userId) => api('/api/projects/' + encodeURIComponent(id) + '/members/' + encodeURIComponent(userId), { method: 'DELETE' }),
  create: payload => api('/api/projects', { method: 'POST', body: JSON.stringify(payload) })
}
