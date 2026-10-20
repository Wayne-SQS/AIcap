import { defineStore } from 'pinia'
import { api } from '@/api/client'

const CURRENT_PROJECT_KEY = 'aiguanli-current-project'

export const useProjectsStore = defineStore('projects', {
  state: () => ({
    projects: [],
    currentProjectId: localStorage.getItem(CURRENT_PROJECT_KEY) || null,
    loading: false,
    loaded: false,
    loadError: ''
  }),
  getters: {
    currentProject: s => s.projects.find(p => p.id === s.currentProjectId) || null
  },
  actions: {
    async loadProjects() {
      this.loading = true
      this.loadError = ''
      try {
        this.projects = await api('/api/projects')
        if (this.currentProjectId && !this.projects.some(p => p.id === this.currentProjectId)) {
          this.currentProjectId = null
          this.persistSelection()
        }
        this.loaded = true
        return this.projects
      } catch (error) {
        this.projects = []
        this.loaded = false
        this.loadError = error.message || '项目列表加载失败'
        throw error
      } finally { this.loading = false }
    },
    async selectProject(projectId) {
      const id = String(projectId || '').trim().toUpperCase()
      if (!id) return null
      if (!this.projects.some(p => p.id === id)) await this.loadProjects()
      if (!this.projects.some(p => p.id === id)) throw new Error('无权访问该项目')
      this.currentProjectId = id
      this.persistSelection()
      return this.currentProject
    },
    persistSelection() {
      if (this.currentProjectId) localStorage.setItem(CURRENT_PROJECT_KEY, this.currentProjectId)
      else localStorage.removeItem(CURRENT_PROJECT_KEY)
    },
    async createProject(payload) {
      const project = await api('/api/projects', { method: 'POST', body: JSON.stringify(payload) })
      this.projects.push(project)
      await this.selectProject(project.id)
      return project
    }
  }
})
