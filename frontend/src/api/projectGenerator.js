import { api } from './client'

export const projectGeneratorApi = {
  create: (mode, request, strategy, projectId) => api('/api/project-generator/runs', { method: 'POST', body: JSON.stringify({ mode, request, strategy, project_id: projectId }) }),
  createNew: (mode, request, project) => api('/api/project-generator/runs', { method: 'POST', body: JSON.stringify({ mode, request, strategy: 'create', context: 'create', project_name: project?.name || '', project_description: project?.description || '', total_sprints: Number(project?.total_sprints) || 3 }) }),
  importFile: (file, strategy, projectId) => { const body = new FormData(); body.append('file', file); return api('/api/project-generator/import?strategy=' + encodeURIComponent(strategy) + '&projectId=' + encodeURIComponent(projectId), { method: 'POST', body }) },
  importNew: (file, project) => { const body = new FormData(); body.append('file', file); const query = new URLSearchParams({ context: 'create', strategy: 'create', projectName: project.name || '', projectDescription: project.description || '', totalSprints: String(project.total_sprints || 0) }); return api('/api/project-generator/import?' + query, { method: 'POST', body }) },
  get: (id, projectId) => api(`/api/project-generator/runs/${id}?projectId=${encodeURIComponent(projectId)}`),
  getNew: id => api(`/api/project-generator/runs/${id}?context=create`),
  updateStory: (runId, storyId, changes, projectId) => api(`/api/project-generator/runs/${runId}/stories/${encodeURIComponent(storyId)}?projectId=${encodeURIComponent(projectId)}`, { method: 'PATCH', body: JSON.stringify(changes) }),
  updateStoryNew: (runId, storyId, changes) => api(`/api/project-generator/runs/${runId}/stories/${encodeURIComponent(storyId)}?context=create`, { method: 'PATCH', body: JSON.stringify(changes) }),
  confirm: (id, replaceConfirmed, projectId) => api(`/api/project-generator/runs/${id}/confirm?projectId=${encodeURIComponent(projectId)}`, { method: 'POST', body: JSON.stringify({ replace_confirmed: replaceConfirmed }) }),
  confirmNew: id => api(`/api/project-generator/runs/${id}/confirm?context=create`, { method: 'POST' }),
  cancel: (id, projectId) => api(`/api/project-generator/runs/${id}/cancel?projectId=${encodeURIComponent(projectId)}`, { method: 'POST' }),
  cancelNew: id => api(`/api/project-generator/runs/${id}/cancel?context=create`, { method: 'POST' })
}
