import { api } from './client'

export const agentApi = {
  config: () => api('/api/agent/config')
}
