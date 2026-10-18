import { createRouter, createWebHashHistory } from 'vue-router'
import { useProjectsStore } from '@/stores/projects'

const workspaceViews = [
  { name: 'overview', label: '项目总览', component: () => import('@/views/OverviewView.vue') },
  { name: 'pool', label: '需求池', component: () => import('@/views/PoolView.vue') },
  { name: 'board', label: '用户故事看板', component: () => import('@/views/BoardView.vue') },
  { name: 'gantt', label: '甘特图', component: () => import('@/views/GanttView.vue') },
  { name: 'members', label: '成员任务图', component: () => import('@/views/MembersView.vue') },
  { name: 'profiles', label: '成员画像', component: () => import('@/views/ProfilesView.vue') },
  { name: 'uml', label: 'UML 图', component: () => import('@/views/UmlView.vue') },
  { name: 'ai', label: 'AI 工作台', component: () => import('@/views/AiView.vue') },
  { name: 'agents', label: '智能体调用中心', component: () => import('@/views/AgentsView.vue') },
  { name: 'meetings', label: '会议工作区', component: () => import('@/views/MeetingView.vue') },
  { name: 'submit-agent', label: '任务提交分析', component: () => import('@/views/SubmitAgentView.vue') },
  { name: 'profile-agent', label: '成员画像分析', component: () => import('@/views/ProfileAgentView.vue') },
  { name: 'ai-demo', label: 'AI 对话演示', component: () => import('@/views/AiDemoView.vue') },
  { name: 'drawing-agent', label: 'Planning Agent', component: () => import('@/views/DrawingAgentView.vue') },
  { name: 'project-generator', label: '智能生成项目图', component: () => import('@/views/ProjectGeneratorView.vue') },
  { name: 'review', label: '提案审核中心', component: () => import('@/views/ReviewView.vue') }
]

const routes = [
  { path: '/', redirect: { name: 'projects' } },
  { path: '/projects', name: 'projects', component: () => import('@/views/ProjectsView.vue') },
  { path: '/projects/new', name: 'project-new', component: () => import('@/views/ProjectGeneratorView.vue'), meta: { generatorContext: 'create', label: '新建项目' } },
  { path: '/projects/:projectId', redirect: to => ({ name: 'project-overview', params: { projectId: to.params.projectId } }) },
  ...workspaceViews.map(({ name, label, component }) => ({
    path: `/projects/:projectId/${name}`,
    name: `project-${name}`,
    component,
    meta: { legacyName: name, label, projectScoped: true }
  })),
  ...workspaceViews.map(({ name, component }) => ({
    path: `/${name}`,
    name,
    component
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
