import { api } from './client'

function withProject(path, projectId) { return projectId ? path + (path.includes('?') ? '&' : '?') + 'projectId=' + encodeURIComponent(projectId) : path }
export const poolApi = {
  list: projectId => api(withProject('/api/pool', projectId)),
  create: (payload, projectId) => api(withProject('/api/pool', projectId), { method: 'POST', body: JSON.stringify(payload) }),
  remove: (id, projectId) => api(withProject('/api/pool/' + id, projectId), { method: 'DELETE' }),
  promote: (id, payload, projectId) => api(withProject('/api/pool/' + id + '/promote', projectId), { method: 'POST', body: JSON.stringify(payload) })
}
