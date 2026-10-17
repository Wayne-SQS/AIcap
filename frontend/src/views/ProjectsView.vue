<script setup>
import { computed, onMounted, ref } from 'vue'
import { projectsApi } from '@/api/projects'
import { useSessionStore } from '@/stores/session'

const session = useSessionStore()
const projects = ref([])
const selected = ref(null)
const members = ref([])
const name = ref('')
const description = ref('')
const editName = ref('')
const editDescription = ref('')
const newUserId = ref('')
const newRole = ref('member')
const error = ref('')
const busy = ref(false)
const canCreate = computed(() => session.currentUser?.role === 'admin')
const canManage = computed(() => selected.value?.member_role === 'owner')

async function refresh() {
  projects.value = await projectsApi.list()
  session.accessibleProjects = projects.value
  if (selected.value) {
    selected.value = projects.value.find(p => p.id === selected.value.id) || null
    members.value = selected.value ? await projectsApi.members(selected.value.id) : []
  }
}
async function select(project) {
  error.value = ''
  selected.value = project
  editName.value = project.name
  editDescription.value = project.description || ''
  try { members.value = await projectsApi.members(project.id) }
  catch (e) { error.value = e.message }
}
async function updateProject() {
  error.value = ''
  if (!editName.value.trim()) { error.value = '请输入项目名称'; return }
  busy.value = true
  try {
    await projectsApi.update(selected.value.id, { name: editName.value.trim(), description: editDescription.value.trim() })
    await refresh()
  } catch (e) { error.value = e.message }
  finally { busy.value = false }
}
async function create() {
  error.value = ''
  if (!name.value.trim()) { error.value = '请输入项目名称'; return }
  busy.value = true
  try {
    const created = await projectsApi.create({ name: name.value.trim(), description: description.value.trim() })
    name.value = ''
    description.value = ''
    await refresh()
    await select(projects.value.find(p => p.id === created.id))
  } catch (e) { error.value = e.message }
  finally { busy.value = false }
}
async function addMember() {
  error.value = ''
  const userId = Number(newUserId.value)
  if (!Number.isInteger(userId) || userId < 1) { error.value = '请输入有效的用户编号'; return }
  busy.value = true
  try {
    await projectsApi.addMember(selected.value.id, userId, newRole.value)
    newUserId.value = ''
    await select(selected.value)
  } catch (e) { error.value = e.message }
  finally { busy.value = false }
}
async function removeMember(member) {
  error.value = ''
  busy.value = true
  try {
    await projectsApi.removeMember(selected.value.id, member.id)
    await select(selected.value)
  } catch (e) { error.value = e.message }
  finally { busy.value = false }
}
async function enterProject() {
  error.value = ''
  busy.value = true
  try { await session.selectProject(selected.value.id) }
  catch (e) { error.value = e.message }
  finally { busy.value = false }
}
onMounted(async () => {
  try { await refresh() }
  catch (e) { error.value = e.message }
})
</script>

<template>
  <section class="view">
    <div class="hero"><div><div class="eyebrow">PROJECTS / 成员范围</div><h1>项目与成员</h1><p>查看所属项目，由项目负责人管理成员。</p></div></div>
    <p v-if="error" role="alert" class="project-error">{{ error }}</p>
    <div v-if="!session.online" class="project-panel">请登录后查看所属项目。</div>
    <template v-else>
      <div v-if="canCreate" class="project-panel">
        <h3>创建项目</h3>
        <form class="project-form" @submit.prevent="create">
          <label>名称 <input v-model="name" maxlength="200" required></label>
          <label>描述 <input v-model="description" maxlength="1000"></label>
          <button :disabled="busy" type="submit">创建</button>
        </form>
      </div>
      <div class="project-panel">
        <h3>我的项目</h3>
        <p v-if="!projects.length">暂无所属项目。</p>
        <div class="project-list">
          <button v-for="item in projects" :key="item.id" :aria-pressed="selected?.id === item.id" @click="select(item)">
            {{ item.name }} <small>#{{ item.id }} · {{ item.member_role === 'owner' ? '负责人' : '成员' }}</small>
          </button>
        </div>
      </div>
      <div v-if="selected" class="project-panel">
        <h3>{{ selected.name }} · 成员</h3>
        <p>{{ selected.description }}</p>
        <form v-if="canManage" class="project-form" @submit.prevent="updateProject">
          <label>项目名称 <input v-model="editName" maxlength="200" required></label>
          <label>项目描述 <input v-model="editDescription" maxlength="1000"></label>
          <button :disabled="busy" type="submit">保存项目信息</button>
        </form>
        <button :disabled="busy" @click="enterProject">进入项目工作区</button>
        <p v-if="selected.id !== 1" class="small">此项目可使用故事、任务、需求池、甘特图和基础报表。</p>
        <ul class="project-members">
          <li v-for="member in members" :key="member.id">
            <span>{{ member.display_name }} <small>#{{ member.id }} · {{ member.member_role === 'owner' ? '负责人' : '成员' }}</small></span>
            <button v-if="canManage && member.id !== session.currentUser?.id" :disabled="busy" @click="removeMember(member)">移除</button>
          </li>
        </ul>
        <form v-if="canManage" class="project-form" @submit.prevent="addMember">
          <label>用户编号 <input v-model="newUserId" type="number" min="1" required></label>
          <label>项目身份 <select v-model="newRole"><option value="member">成员</option><option value="owner">负责人</option></select></label>
          <button :disabled="busy" type="submit">添加或更新成员</button>
        </form>
      </div>
    </template>
  </section>
</template>

<style scoped>
.project-panel{background:var(--paper);border:2px solid var(--ink);box-shadow:var(--shadow);padding:20px;margin:18px 0}
.project-panel h3{margin:0 0 12px}
.project-form{display:flex;align-items:end;gap:12px;flex-wrap:wrap}
.project-form label{display:grid;gap:4px}
.project-form input,.project-form select{background:white;border:2px solid var(--ink);padding:7px;min-width:160px}
.project-list{display:flex;gap:10px;flex-wrap:wrap}
.project-list button[aria-pressed="true"]{background:var(--green)}
.project-list small,.project-members small{display:block;color:var(--muted)}
.project-members{padding:0;list-style:none}
.project-members li{display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid var(--line);padding:8px 0}
.project-error{color:#a32727;font-weight:700}
</style>
