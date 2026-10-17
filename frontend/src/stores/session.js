import { defineStore } from 'pinia'
import { authApi } from '@/api/auth'
import { health, setAuthToken, getAuthToken, getProjectId, setProjectId } from '@/api/client'
import { projectsApi } from '@/api/projects'
import { useToast } from '@/composables/useToast'

/* 会话与在线/离线模式:对齐旧版 index.html L1262-1341 的
   apiMode/authToken/currentUser/bootstrap/doLogin/logout/updateChip/openLogin 全套语义 */
export const useSessionStore = defineStore('session', {
  state: () => ({
    apiMode: false,
    authToken: getAuthToken(),
    currentUser: null,
    accessibleProjects: [],
    currentProjectId: getProjectId(),
    loginOpen: false,
    booting: false,
    sessionEpoch: 0
  }),
  getters: {
    isViewer: s => s.apiMode && s.currentUser && (s.currentUser.can_write === false || s.currentUser.role === 'viewer'),
    /** 在线且角色为 admin/owner —— 与后端 `Roles.reviewer()` 同口径(删除故事/会议等入口的后端要求);
     *  离线演示模式下后端不可达,不参与判定,写入口不受限 */
    canReview: s => s.apiMode && !!s.currentUser && (s.currentUser.can_manage === true || ['admin', 'owner'].includes(s.currentUser.role)),
    online: s => s.apiMode && !!s.currentUser
  },
  actions: {
    roleName(r) {
      return ({ admin: '管理员', owner: '负责人', member: '成员', viewer: '查看者' })[r] || r
    },
    async selectProject(id) {
      const selected = this.accessibleProjects.find(p => Number(p.id) === Number(id))
      if (!selected) throw new Error('你不是此项目的成员')
      this.currentProjectId = Number(id)
      setProjectId(id)
      await useProjectStoreSafe()?.loadAll()
      const { default: router } = await import('@/router')
      await router.push({ name: 'overview' })
    },
    openLogin() { this.loginOpen = true },
    handleUnauthorized() {
      // 对齐旧版 401 分支:清会话转离线 + toast + 弹登录
      this.sessionEpoch++
      this.apiMode = false
      this.authToken = null
      this.currentUser = null
      this.accessibleProjects = []
      setAuthToken(null)
      useProjectStoreSafe()?.clearServerData()
      const { notify } = useToast()
      notify('登录已过期，请重新登录')
      this.openLogin()
    },
    async bootstrap() {
      if (this.booting) return
      this.booting = true
      try {
        this.apiMode = await health()
        if (this.apiMode) {
          if (this.authToken) {
            try {
              this.currentUser = await authApi.me()
              this.accessibleProjects = await projectsApi.list()
              const project = useProjectStoreSafe()
              const selected = this.accessibleProjects.find(p => Number(p.id) === getProjectId())
                || this.accessibleProjects.find(p => Number(p.id) === 1) || this.accessibleProjects[0]
              if (selected) {
                this.currentProjectId = Number(selected.id)
                setProjectId(selected.id)
                if (project) await project.loadAll()
              } else {
                project?.clearServerData()
                await goProjects()
              }
            } catch (e) {
              this.authToken = null
              this.accessibleProjects = []
              setAuthToken(null)
              useProjectStoreSafe()?.clearServerData()
              this.openLogin()
            }
          } else {
            // 在线但尚未认证时，不允许启动阶段的本地缓存继续作为正式项目数据。
            useProjectStoreSafe()?.clearServerData()
            this.openLogin()
          }
        } else {
          const project = useProjectStoreSafe()
          project?.restoreLocal()
          // 对齐旧版 bootstrap:离线且浏览器未允许保存时的提示
          if (project && !project.storageOK) project.savehint = '浏览器未允许保存 · 数据仅在本次打开期间保留'
        }
      } finally {
        this.booting = false
      }
    },
    async login(username, password) {
      const data = await authApi.login(username, password)
      const token = data.access_token
      const user = data.user
      const epoch = ++this.sessionEpoch
      this.authToken = token
      this.currentUser = user
      setAuthToken(this.authToken)
      this.apiMode = true
      this.loginOpen = false
      const projects = await projectsApi.list()
      if (this.sessionEpoch !== epoch || !this.apiMode) return
      this.accessibleProjects = projects
      const project = useProjectStoreSafe()
      const selected = this.accessibleProjects.find(p => Number(p.id) === getProjectId())
        || this.accessibleProjects.find(p => Number(p.id) === 1) || this.accessibleProjects[0]
      if (selected) {
        this.currentProjectId = Number(selected.id)
        setProjectId(selected.id)
        if (project) await project.loadAll()
      } else {
        project?.clearServerData()
        await goProjects()
      }
      if (this.sessionEpoch !== epoch || !this.apiMode) return
      const { notify } = useToast()
      notify('欢迎，' + user.display_name)
    },
    async logout() {
      this.sessionEpoch++
      this.authToken = null
      this.currentUser = null
      this.accessibleProjects = []
      setAuthToken(null)
      this.apiMode = false
      useProjectStoreSafe()?.restoreLocal()
      const { notify } = useToast()
      notify('已退出登录，回到演示数据')
    },
    async goOffline() {
      // 登录弹窗的「离线演示模式」按钮
      this.sessionEpoch++
      this.loginOpen = false
      this.apiMode = false
      this.authToken = null
      this.currentUser = null
      this.accessibleProjects = []
      setAuthToken(null)
      useProjectStoreSafe()?.restoreLocal()
      const { notify } = useToast()
      notify('已切换离线演示模式')
    },
    onLoginClosed() {
      // 对齐旧版 dialog close 处理:在线但未登录 → 回退离线
      if (this.apiMode && !this.currentUser) {
        this.apiMode = false
        useProjectStoreSafe()?.clearServerData()
      }
    }
  }
})

/* 延迟引用 project store,避免模块级循环依赖 */
import { useProjectStore } from '@/stores/project'
function useProjectStoreSafe() {
  try { return useProjectStore() } catch (e) { return null }
}

async function goProjects() {
  const { default: router } = await import('@/router')
  await router.push({ name: 'projects' })
}
