import { api } from './client'

export const authApi = {
  login: (username, password) => api('/api/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) }),
  me: () => api('/api/auth/me'),
  users: () => api('/api/auth/users')
}
