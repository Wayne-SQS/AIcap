import { api } from './client'
export const projectsApi = {
  list: () => api('/api/projects'),
  get: id => api('/api/projects/' + encodeURIComponent(id)),
  members: id => api('/api/projects/' + encodeURIComponent(id) + '/members'),
  create: payload => api('/api/projects', { method: 'POST', body: JSON.stringify(payload) })
}
