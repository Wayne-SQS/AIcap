<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useProjectsStore } from '@/stores/projects'
import { useProjectStore } from '@/stores/project'

const router = useRouter()
const projects = useProjectsStore()
const project = useProjectStore()
const error = ref('')

onMounted(async () => {
  try { await projects.loadProjects() } catch (e) { error.value = e.message }
})

async function enter(row) {
  error.value = ''
  try {
    await projects.selectProject(row.id)
    await project.loadAll(row.id)
    router.push({ name: 'project-overview', params: { projectId: row.id } })
  } catch (e) { error.value = e.message }
}

</script>

<template>
  <section class="view projects-view">
    <header class="hero">
      <div><div class="eyebrow">WORKSPACES / PROJECTS</div><h1>我的项目</h1><p>选择一个项目进入工作区。</p></div>
      <button class="primary" @click="router.push({ name: 'project-new' })">+ 新建项目</button>
    </header>
    <p v-if="error" class="projects-error" role="alert">{{ error }}</p>
    <div class="projects-grid">
      <article v-for="row in projects.projects" :key="row.id" class="projects-card" :class="{ 'is-current': row.id === projects.currentProjectId }">
        <div class="projects-card-top">
          <span class="projects-id">{{ row.id }}</span>
          <span class="projects-status">{{ row.status }}</span>
        </div>
        <div class="projects-card-body">
          <h2>{{ row.name }}</h2>
          <p class="projects-description">{{ row.description || '暂无项目描述' }}</p>
        </div>
        <div class="projects-card-footer">
          <div class="projects-stats" aria-label="项目统计">
            <span><b>{{ row.story_count ?? 0 }}</b><small>Story</small></span>
            <span><b>{{ row.task_count ?? 0 }}</b><small>Task</small></span>
            <span><b>{{ row.member_count ?? 0 }}</b><small>成员</small></span>
          </div>
          <button class="primary projects-enter" @click="enter(row)">进入项目</button>
        </div>
      </article>
      <p v-if="!projects.loading && !projects.projects.length" class="small">当前账号没有可访问的项目。</p>
    </div>
  </section>
</template>
