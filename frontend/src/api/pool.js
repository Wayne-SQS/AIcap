import { api } from './client'

export const poolApi = {
  list: () => api('/api/pool'),
  create: payload => api('/api/pool', { method: 'POST', body: JSON.stringify(payload) }),
  remove: id => api('/api/pool/' + id, { method: 'DELETE' }),
  promote: (id, payload) => api('/api/pool/' + id + '/promote', { method: 'POST', body: JSON.stringify(payload) })
}
