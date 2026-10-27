<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useProjectsStore } from '@/stores/projects'
import { useProjectStore } from '@/stores/project'
import { projectsApi } from '@/api/projects'
import { useSessionStore } from '@/stores/session'

const router = useRouter()
const projects = useProjectsStore()
const project = useProjectStore()
const session = useSessionStore()
const error = ref('')
const managing = ref(null)
const members = ref([])
const editName = ref('')
const editDescription = ref('')
const newUserId = ref('')
const newRole = ref('member')
const busy = ref(false)

onMounted(async () => {
  try { await projects.loadProjects() } catch { /* projects.loadError 提供重试入口 */ }
})

async function retryProjects() {
  try { await projects.loadProjects() } catch { /* 错误已写入 projects.loadError */ }
}

async function enter(row) {
  error.value = ''
  try {
    await projects.selectProject(row.id)
    await project.loadAll(row.id)
    router.push({ name: 'project-overview', params: { projectId: row.id } })
  } catch (e) { error.value = e.message }
}

async function manage(row) {
  if (managing.value?.id === row.id) { managing.value = null; return }
  error.value = ''
  try {
    managing.value = row
    editName.value = row.name
    editDescription.value = row.description || ''
    members.value = await projectsApi.members(row.id)
  } catch (e) { error.value = e.message }
}
async function saveProject() {
  if (!managing.value?.can_manage) return
  busy.value = true; error.value = ''
  try {
    await projectsApi.update(managing.value.id, { name: editName.value.trim(), description: editDescription.value.trim() })
    await projects.loadProjects()
    managing.value = projects.projects.find(p => p.id === managing.value.id)
  } catch (e) { error.value = e.message }
  finally { busy.value = false }
}
async function saveMember() {
  if (!managing.value?.can_manage) return
  const userId = Number(newUserId.value)
  if (!Number.isInteger(userId) || userId < 1) { error.value = '请输入有效的用户编号'; return }
  busy.value = true; error.value = ''
  try {
    await projectsApi.updateMember(managing.value.id, userId, { member_role: newRole.value })
    members.value = await projectsApi.members(managing.value.id)
    newUserId.value = ''
    await projects.loadProjects()
  } catch (e) { error.value = e.message }
  finally { busy.value = false }
}
async function removeMember(member) {
  if (!managing.value?.can_manage || member.user_id === session.currentUser?.id) return
  busy.value = true; error.value = ''
  try {
    await projectsApi.removeMember(managing.value.id, member.user_id)
    members.value = await projectsApi.members(managing.value.id)
    await projects.loadProjects()
  } catch (e) { error.value = e.message }
  finally { busy.value = false }
}

</script>

<template>
  <section class="view projects-view">
    <header class="hero">
      <div><div class="eyebrow">WORKSPACES / PROJECTS</div><h1>我的项目</h1><p>选择一个项目进入工作区。</p></div>
      <button class="primary" @click="router.push({ name: 'project-new' })">+ 新建项目</button>
    </header>
    <p v-if="error" class="projects-error" role="alert">{{ error }}</p>
    <div v-if="projects.loadError" class="projects-error" role="alert">
      项目列表加载失败：{{ projects.loadError }} <button type="button" :disabled="projects.loading" @click="retryProjects">重试</button>
    </div>
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
          <button type="button" @click="manage(row)">项目成员</button>
        </div>
      </article>
      <p v-if="projects.loaded && !projects.loading && !projects.projects.length" class="small">当前账号没有可访问的项目。</p>
    </div>
    <section v-if="managing" class="projects-manage">
      <h2>{{ managing.name }} · 项目成员</h2>
      <form v-if="managing.can_manage" @submit.prevent="saveProject">
        <label>项目名称 <input v-model="editName" maxlength="200" required></label>
        <label>项目描述 <input v-model="editDescription" maxlength="1000"></label>
        <button :disabled="busy" type="submit">保存项目信息</button>
      </form>
      <ul>
        <li v-for="member in members" :key="member.user_id">
          <span>用户 #{{ member.user_id }} · {{ member.project_role }} · {{ member.weekly_capacity_hours }}h/周</span>
          <button v-if="managing.can_manage && member.user_id !== session.currentUser?.id" :disabled="busy" @click="removeMember(member)">移除</button>
        </li>
      </ul>
      <form v-if="managing.can_manage" @submit.prevent="saveMember">
        <label>用户编号 <input v-model="newUserId" type="number" min="1" required></label>
        <label>项目身份 <select v-model="newRole"><option value="member">成员</option><option value="owner">负责人</option><option value="viewer">查看者</option></select></label>
        <button :disabled="busy" type="submit">添加或更新成员</button>
      </form>
    </section>
  </section>
</template>

<style scoped>
.projects-manage{margin-top:18px;padding:18px;border:2px solid var(--ink);background:var(--paper)}
.projects-manage form{display:flex;gap:10px;align-items:end;flex-wrap:wrap;margin:12px 0}
.projects-manage label{display:grid;gap:4px;font-size:12px}
.projects-manage input,.projects-manage select{min-width:150px}
.projects-manage ul{padding:0;list-style:none}
.projects-manage li{display:flex;gap:12px;justify-content:space-between;border-bottom:1px solid var(--line);padding:8px 0}
</style>
