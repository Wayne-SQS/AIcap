import { api } from './client'

export const projectGeneratorApi = {
  create: (mode, request, strategy) => api('/api/project-generator/runs', { method: 'POST', body: JSON.stringify({ mode, request, strategy }) }),
  importFile: (file, strategy) => { const body = new FormData(); body.append('file', file); return api('/api/project-generator/import?strategy=' + encodeURIComponent(strategy), { method: 'POST', body }) },
  get: id => api('/api/project-generator/runs/' + id),
  updateStory: (runId, storyId, changes) => api('/api/project-generator/runs/' + runId + '/stories/' + encodeURIComponent(storyId), { method: 'PATCH', body: JSON.stringify(changes) }),
  confirm: (id, replaceConfirmed) => api('/api/project-generator/runs/' + id + '/confirm', { method: 'POST', body: JSON.stringify({ replace_confirmed: replaceConfirmed }) }),
  cancel: id => api('/api/project-generator/runs/' + id + '/cancel', { method: 'POST' })
}
