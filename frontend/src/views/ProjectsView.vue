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
const deleteArmed = ref(false)
const deleteConfirmation = ref('')

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
  if (managing.value?.id === row.id) { managing.value = null; deleteArmed.value = false; deleteConfirmation.value = ''; return }
  error.value = ''
  deleteArmed.value = false
  deleteConfirmation.value = ''
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

async function deleteProject() {
  const row = managing.value
  if (!row?.can_manage || !deleteArmed.value || deleteConfirmation.value.trim() !== row.name || busy.value) return
  busy.value = true; error.value = ''
  try {
    await projectsApi.remove(row.id)
    projects.projects = projects.projects.filter(item => item.id !== row.id)
    if (projects.currentProjectId === row.id) {
      projects.currentProjectId = null
      projects.persistSelection()
      project.clearServerData()
    }
    managing.value = null
    members.value = []
    deleteArmed.value = false
    deleteConfirmation.value = ''
  } catch (e) { error.value = e.message || '删除项目失败' }
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
      <div v-if="managing.can_manage" class="projects-delete">
        <button v-if="!deleteArmed" type="button" :disabled="busy" @click="deleteArmed = true">删除项目</button>
        <div v-else>
          <p>删除后所有成员都无法访问此项目；数据仍保留在数据库中。</p>
          <label>输入项目名称「{{ managing.name }}」以确认
            <input v-model="deleteConfirmation" :disabled="busy" autocomplete="off">
          </label>
          <button type="button" :disabled="busy" @click="deleteArmed = false; deleteConfirmation = ''">取消</button>
          <button type="button" :disabled="busy || deleteConfirmation.trim() !== managing.name" @click="deleteProject">{{ busy ? '正在删除…' : '确认删除项目' }}</button>
        </div>
      </div>
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
.projects-delete{margin-top:20px;padding-top:16px;border-top:1px solid var(--line)}
.projects-delete input{display:block;margin:8px 0 12px;min-width:min(100%,280px)}
.projects-delete button+button{margin-left:8px}
</style>
