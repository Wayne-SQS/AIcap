import { createRouter, createWebHashHistory } from 'vue-router'
import { useProjectsStore } from '@/stores/projects'

const workspaceViews = [
  ['overview', 'overview', '项目总览'], ['pool', 'pool', '需求池'], ['board', 'board', '用户故事看板'],
  ['gantt', 'gantt', '甘特图'], ['members', 'members', '成员任务图'], ['profiles', 'profiles', '成员画像'],
  ['uml', 'uml', 'UML 图'], ['ai', 'ai', 'AI 助手'], ['drawing-agent', 'drawing-agent', '画图智能体'],
  ['project-generator', 'project-generator', '智能生成项目图'], ['review', 'review', 'AI 审核中心']
]

const routes = [
  { path: '/', redirect: { name: 'projects' } },
  { path: '/projects', name: 'projects', component: () => import('@/views/ProjectsView.vue') },
  { path: '/projects/new', name: 'project-new', component: () => import('@/views/ProjectGeneratorView.vue'), meta: { generatorContext: 'create', label: '新建项目' } },
  { path: '/projects/:projectId', redirect: to => ({ name: 'project-overview', params: { projectId: to.params.projectId } }) },
  ...workspaceViews.map(([name, legacyName, label]) => ({
    path: `/projects/:projectId/${name}`,
    name: `project-${name}`,
    component: () => import(`@/views/${name === 'overview' ? 'OverviewView' : name === 'pool' ? 'PoolView' : name === 'board' ? 'BoardView' : name === 'gantt' ? 'GanttView' : name === 'members' ? 'MembersView' : name === 'profiles' ? 'ProfilesView' : name === 'uml' ? 'UmlView' : name === 'ai' ? 'AiView' : name === 'drawing-agent' ? 'DrawingAgentView' : name === 'project-generator' ? 'ProjectGeneratorView' : 'ReviewView'}.vue`),
    meta: { legacyName, label, projectScoped: true }
  })),
  ...workspaceViews.map(([name, legacyName]) => ({
    path: `/${name}`,
    name: legacyName,
    component: () => import(`@/views/${name === 'overview' ? 'OverviewView' : name === 'pool' ? 'PoolView' : name === 'board' ? 'BoardView' : name === 'gantt' ? 'GanttView' : name === 'members' ? 'MembersView' : name === 'profiles' ? 'ProfilesView' : name === 'uml' ? 'UmlView' : name === 'ai' ? 'AiView' : name === 'drawing-agent' ? 'DrawingAgentView' : name === 'project-generator' ? 'ProjectGeneratorView' : 'ReviewView'}.vue`)
  }))
]

const router = createRouter({ history: createWebHashHistory(), routes })
router.beforeEach(to => {
  if (!to.meta.projectScoped) return
  const projectId = String(to.params.projectId || '').trim().toUpperCase()
  if (!projectId) return { name: 'projects' }
  const projects = useProjectsStore()
  projects.currentProjectId = projectId
  projects.persistSelection()
})
router.afterEach(() => { window.scrollTo({ top: 0 }) })
export default router
