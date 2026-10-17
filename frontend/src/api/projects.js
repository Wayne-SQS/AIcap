import { api } from './client'

export const projectsApi = {
  list: () => api('/api/projects'),
  create: data => api('/api/projects', { method: 'POST', body: JSON.stringify(data) }),
  update: (id, data) => api(`/api/projects/${id}`, { method: 'PATCH', body: JSON.stringify(data) }),
  members: id => api(`/api/projects/${id}/members`),
  addMember: (id, userId, memberRole = 'member') => api(`/api/projects/${id}/members/${userId}`, {
    method: 'PUT', body: JSON.stringify({ member_role: memberRole })
  }),
  removeMember: (id, userId) => api(`/api/projects/${id}/members/${userId}`, { method: 'DELETE' })
}
