import { api } from './client'

/** UML 布局:按项目存一份 JSON,任何成员可读,可写角色可保存。 */
export const umlLayoutApi = {
  get: projectId => api(`/api/projects/${encodeURIComponent(projectId)}/uml-layout`),
  save: (projectId, layout) => api(`/api/projects/${encodeURIComponent(projectId)}/uml-layout`, {
    method: 'PUT',
    body: JSON.stringify({ layout })
  })
}
