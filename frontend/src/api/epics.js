import { api } from './client'

export const epicsApi = {
  list: () => api('/api/epics'),
  create: payload => api('/api/epics', { method: 'POST', body: JSON.stringify(payload) }),
  patch: (id, payload) => api('/api/epics/' + id, { method: 'PATCH', body: JSON.stringify(payload) }),
  remove: id => api('/api/epics/' + id, { method: 'DELETE' })
}
