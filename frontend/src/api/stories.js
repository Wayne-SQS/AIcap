import { api } from './client'
function withProject(path, projectId) { return projectId ? path + (path.includes('?') ? '&' : '?') + 'projectId=' + encodeURIComponent(projectId) : path }
export const storiesApi = {
  list: projectId => api(withProject('/api/stories', projectId)),
  create: (payload, projectId) => api(withProject('/api/stories', projectId), { method: 'POST', body: JSON.stringify(payload) }),
  patch: (id, payload, projectId) => api(withProject('/api/stories/' + id, projectId), { method: 'PATCH', body: JSON.stringify(payload) }),
  remove: (id, undone, projectId) => api(withProject('/api/stories/' + id + '?undone=' + undone, projectId), { method: 'DELETE' }),
  logs: projectId => api(withProject('/api/stories/logs', projectId))
}
