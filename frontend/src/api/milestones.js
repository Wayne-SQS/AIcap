import { api } from './client'

export const milestonesApi = {
  list: () => api('/api/milestones')
}
