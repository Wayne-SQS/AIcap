import { api } from './client'
function withProject(path, projectId) { return projectId ? path + (path.includes('?') ? '&' : '?') + 'projectId=' + encodeURIComponent(projectId) : path }
export const tasksApi = {
  list: projectId => api(withProject('/api/tasks', projectId)),
  patch: (id, payload, projectId) => api(withProject('/api/tasks/' + id, projectId), { method: 'PATCH', body: JSON.stringify(payload) })
}
