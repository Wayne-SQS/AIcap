<script setup>
import { computed, ref } from 'vue'
import { useProjectStore } from '@/stores/project'
import { useProjectsStore } from '@/stores/projects'
import { useSessionStore } from '@/stores/session'
import { useToast } from '@/composables/useToast'
import { epicsApi } from '@/api/epics'

const project = useProjectStore()
const projects = useProjectsStore()
const session = useSessionStore()
const { notify } = useToast()
const dialog = ref(null)
const editingId = ref(null)
const name = ref('')
const description = ref('')
const canManage = computed(() => !session.apiMode || ['owner', 'admin'].includes(projects.currentProject?.project_role))

function reset() { editingId.value = null; name.value = ''; description.value = '' }
function open() { reset(); dialog.value?.showModal() }
function edit(epic) { editingId.value = epic.id; name.value = epic.name; description.value = epic.description || '' }
function storyCount(epic) { return project.stories.filter(s => s.epicId === epic.id).length }

async function save() {
  if (!canManage.value) return
  const body = { name: name.value.trim(), description: description.value.trim() }
  if (!body.name) { notify('请填写史诗名称'); return }
  try {
    if (session.apiMode) {
      if (editingId.value) await epicsApi.patch(editingId.value, body, projects.currentProjectId)
      else await epicsApi.create(body, projects.currentProjectId)
      await project.loadAll(projects.currentProjectId)
    } else if (editingId.value) {
      Object.assign(project.epics.find(e => e.id === editingId.value), body)
      project.persist()
    } else {
      const id = Math.max(0, ...project.epics.map(e => e.id)) + 1
      project.epics.push({ id, code: 'E' + id, ...body })
      project.persist()
    }
    notify(editingId.value ? '史诗已更新' : '史诗已创建')
    reset()
  } catch (error) { notify(error.message) }
}

async function remove(epic) {
  if (!canManage.value) return
  if (storyCount(epic)) { notify('请先调整关联故事的史诗归属'); return }
  if (!confirm(`删除 ${epic.code} · ${epic.name}？`)) return
  try {
    if (session.apiMode) {
      await epicsApi.remove(epic.id, projects.currentProjectId)
      await project.loadAll(projects.currentProjectId)
    } else {
      project.epics = project.epics.filter(e => e.id !== epic.id)
      project.persist()
    }
    if (editingId.value === epic.id) reset()
    notify('史诗已删除')
  } catch (error) { notify(error.message) }
}

defineExpose({ open })
</script>

<template>
  <dialog id="epic-manager" ref="dialog" aria-labelledby="epic-manager-title">
    <header><h2 id="epic-manager-title">维护项目史诗</h2><button type="button" aria-label="关闭" @click="dialog.close()">×</button></header>
    <div class="epic-manager-body">
      <p class="small">故事可在编辑弹窗中归属史诗；删除史诗前，请先调整关联故事。</p>
      <ul class="epic-manager-list">
        <li v-for="epic in project.epics" :key="epic.id">
          <div><b>{{ epic.code }} · {{ epic.name }}</b><small>{{ storyCount(epic) }} 条故事{{ epic.description ? ' · ' + epic.description : '' }}</small></div>
          <span><button type="button" :disabled="!canManage" @click="edit(epic)">编辑</button><button type="button" :disabled="!canManage || !!storyCount(epic)" @click="remove(epic)">删除</button></span>
        </li>
      </ul>
      <form class="epic-manager-form" @submit.prevent="save">
        <h3>{{ editingId ? '编辑史诗' : '新增史诗' }}</h3>
        <label>名称<input v-model="name" name="epic_name" maxlength="100" required :disabled="!canManage"></label>
        <label>描述<textarea v-model="description" name="epic_description" maxlength="500" :disabled="!canManage"></textarea></label>
        <div class="actions"><button v-if="editingId" type="button" @click="reset">取消编辑</button><button class="primary" type="submit" :disabled="!canManage">保存史诗</button></div>
      </form>
    </div>
  </dialog>
</template>

<style scoped>
.epic-manager-body{padding:16px;max-height:70vh;overflow:auto}
.epic-manager-list{padding:0;margin:12px 0;list-style:none}
.epic-manager-list li{display:flex;justify-content:space-between;gap:10px;align-items:center;border-bottom:1px solid var(--line);padding:9px 0}
.epic-manager-list li div{display:grid;gap:3px}.epic-manager-list small{color:var(--muted)}
.epic-manager-list li span{display:flex;gap:6px}
.epic-manager-form{display:grid;gap:10px;border-top:2px solid var(--ink);padding-top:12px}
.epic-manager-form h3{margin:0;font-size:15px}
.epic-manager-form label{display:grid;gap:4px;font-size:12px;font-weight:700}
.epic-manager-form input,.epic-manager-form textarea{width:100%}
.epic-manager-form .actions{display:flex;justify-content:flex-end;gap:8px}
</style>
