import { api } from './client'

const withProject = (path, projectId) => path + '?projectId=' + encodeURIComponent(projectId)

export const epicsApi = {
  list: projectId => api(withProject('/api/epics', projectId)),
  create: (payload, projectId) => api(withProject('/api/epics', projectId), { method: 'POST', body: JSON.stringify(payload) }),
  patch: (id, payload, projectId) => api(withProject('/api/epics/' + id, projectId), { method: 'PATCH', body: JSON.stringify(payload) }),
  remove: (id, projectId) => api(withProject('/api/epics/' + id, projectId), { method: 'DELETE' })
}
