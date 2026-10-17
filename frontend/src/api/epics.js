import { api } from './client'
function withProject(path, projectId) { return projectId ? path + (path.includes('?') ? '&' : '?') + 'projectId=' + encodeURIComponent(projectId) : path }

export const epicsApi = {
  list: projectId => api(withProject('/api/epics', projectId)),
  create: (payload, projectId) => api(withProject('/api/epics', projectId), { method: 'POST', body: JSON.stringify(payload) }),
  patch: (id, payload, projectId) => api(withProject('/api/epics/' + id, projectId), { method: 'PATCH', body: JSON.stringify(payload) }),
  remove: (id, projectId) => api(withProject('/api/epics/' + id, projectId), { method: 'DELETE' })
}
