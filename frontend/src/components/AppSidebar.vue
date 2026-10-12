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
  { title: '项目空间', items: [['overview','◈','项目总览'],['pool','◆','需求池'],['board','▦','用户故事看板'],['gantt','▤','甘特图'],['members','▥','成员任务图'],['profiles','▤','成员画像'],['uml','◇','UML 图']] },
  { title: '智能协作', items: [['ai','✦','AI 助手'],['drawing-agent','✎','画图智能体'],['review','✓','AI 审核中心']] }
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
        <div class="navtitle">{{ group.title }}</div>
        <div v-for="[view, icon, label] in group.items" :key="view" class="navitem" :class="{ active: route.meta.legacyName === view || route.name === view }" :data-view="view" @click="go(view)"><span class="navicon">{{ icon }}</span>{{ label }}</div>
      </template>
    </nav>
    <div class="sidebottom"><span class="eyebrow">{{ activeProject?.id || 'PROJECT' }}</span><p class="small">{{ activeProject?.name || (session.currentUser ? '请选择项目' : '工作区') }}</p></div>
  </aside>
</template>
