<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useProjectsStore } from '@/stores/projects'
import { useProjectStore } from '@/stores/project'
import { useSessionStore } from '@/stores/session'

const route = useRoute()
const router = useRouter()
const projects = useProjectsStore()
const project = useProjectStore()
const session = useSessionStore()
const projectPicker = ref(null)
const projectMenuOpen = ref(false)
const navGroups = [
  {
    title: '项目空间',
    items: [
      { view: 'overview', icon: '◈', label: '项目总览' },
      { view: 'pool', icon: '◆', label: '需求池' },
      { view: 'board', icon: '▦', label: '用户故事看板' },
      { view: 'gantt', icon: '▤', label: '甘特图' },
      { view: 'members', icon: '▥', label: '成员任务图' },
      { view: 'profiles', icon: '▤', label: '成员画像' },
      { view: 'uml', icon: '◇', label: 'UML 图' }
    ]
  },
  {
    title: '智能协作',
    topGap: true,
    items: [
      { view: 'ai', icon: '✦', label: 'AI 工作台' },
      { view: 'meetings', icon: '◉', label: '会议工作区' },
      { view: 'submit-agent', icon: '↗', label: '任务提交分析' },
      { view: 'profile-agent', icon: '◌', label: '成员画像分析' },
      { view: 'drawing-agent', icon: '✎', label: 'Planning Agent · 项目规划' },
      { view: 'project-generator', icon: '✚', label: '智能生成项目图' },
      { view: 'review', icon: '✓', label: '提案审核中心' }
    ]
  }
]
const scopedRoute = computed(() => Boolean(route.meta.projectScoped))
const activeProject = computed(() => projects.currentProject)
const selectedProjectName = computed(() => activeProject.value?.name || '选择项目')

async function switchProject(id) {
  if (!id) return
  projectMenuOpen.value = false
  await projects.selectProject(id)
  await project.loadAll(id)
  const view = route.meta.legacyName || 'overview'
  router.push({ name: `project-${view}`, params: { projectId: id } })
}
function closeProjectMenu(event) {
  if (!projectPicker.value?.contains(event.target)) projectMenuOpen.value = false
}
function onProjectPickerKeydown(event) {
  if (event.key === 'Escape') projectMenuOpen.value = false
}
onMounted(() => document.addEventListener('pointerdown', closeProjectMenu))
onBeforeUnmount(() => document.removeEventListener('pointerdown', closeProjectMenu))
function go(name) {
  if (!projects.currentProjectId) return router.push({ name: 'projects' })
  router.push({ name: `project-${name}`, params: { projectId: projects.currentProjectId } })
}
</script>

<template>
  <aside class="sidebar">
    <div class="brand"><span class="mark" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i></span><span>爱管理<small>TEAM WORKSPACE</small></span></div>
    <div class="project-switcher">
      <label id="project-switch-label">当前项目 / PROJECT</label>
      <div ref="projectPicker" class="project-picker" @keydown="onProjectPickerKeydown">
        <button
          id="project-switch"
          type="button"
          class="project-picker-trigger"
          aria-haspopup="listbox"
          :aria-expanded="projectMenuOpen"
          aria-labelledby="project-switch-label project-switch"
          :title="activeProject?.name || '选择项目'"
          @click="projectMenuOpen = !projectMenuOpen"
        >
          <span>{{ selectedProjectName }}</span><i aria-hidden="true"></i>
        </button>
        <div v-if="projectMenuOpen" class="project-picker-menu" role="listbox" aria-labelledby="project-switch-label">
          <button type="button" class="project-picker-option is-placeholder" disabled>选择项目</button>
          <button
            v-for="item in projects.projects"
            :key="item.id"
            type="button"
            class="project-picker-option"
            :class="{ 'is-selected': item.id === projects.currentProjectId }"
            role="option"
            :aria-selected="item.id === projects.currentProjectId"
            :title="item.name"
            @click="switchProject(item.id)"
          >{{ item.name }}</button>
        </div>
      </div>
      <button class="project-list-link" @click="router.push({ name: 'projects' })">我的项目</button>
    </div>
    <nav aria-label="功能导航">
      <template v-for="group in navGroups" :key="group.title">
        <div class="navtitle" :style="group.topGap ? 'margin-top:24px' : ''">{{ group.title }}</div>
        <div
          v-for="item in group.items"
          :key="item.view"
          class="navitem"
          :class="{ active: route.meta.legacyName === item.view || route.name === item.view }"
          :data-view="item.view"
          @click="go(item.view)"
          @keydown.enter.prevent="go(item.view)"
          @keydown.space.prevent="go(item.view)"
          role="link"
          tabindex="0"
          :aria-current="route.meta.legacyName === item.view || route.name === item.view ? 'page' : undefined"
        ><span class="navicon" aria-hidden="true">{{ item.icon }}</span>{{ item.label }}</div>
      </template>
    </nav>
    <div class="sidebottom"><span class="eyebrow">{{ activeProject?.id || 'PROJECT' }}</span><p class="small">{{ activeProject?.name || (session.currentUser ? '请选择项目' : '工作区') }}</p></div>
  </aside>
</template>
