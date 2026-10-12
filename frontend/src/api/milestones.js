import { api } from './client'
export const milestonesApi = { list: projectId => api(projectId ? '/api/milestones?projectId=' + encodeURIComponent(projectId) : '/api/milestones') }
