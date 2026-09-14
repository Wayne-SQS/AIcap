<script setup>
import { ref, reactive, nextTick, computed } from 'vue'
import { useProjectStore, taskRefs } from '@/stores/project'
import { useSessionStore } from '@/stores/session'
import { useToast } from '@/composables/useToast'
import { usePermissionGuard, READONLY_TITLE } from '@/composables/usePermissionGuard'
import { tasksApi } from '@/api/tasks'
import { derivedTaskSprints } from '@/data/seed'
import { memberUserId, memberIndex } from '@/data/memberIdentity'

/* 任务编辑弹窗(移植 legacy 新版 #task-editor,交互按 Vue 重写)
   在线:PATCH /api/tasks/{id},字段与后端契约一致(name/owner_id/hours/week_start/week_end/story_ref/depends_on)
   离线:直接改本地任务并重算 sprints
   校验前后端同口径:周序、US/T 编号合法性、关联故事与前置任务存在性、不能依赖自身 */
const project = useProjectStore()
const session = useSessionStore()
const { notify } = useToast()
const { guard } = usePermissionGuard()

const dialogEl = ref(null)
const editingId = ref(null)
const task = computed(() => project.tasks.find(t => t.id === editingId.value) || null)
const form = reactive({ name: '', owner: '0', hours: 0, weekStart: '1', weekEnd: '1', storyRef: '', dependsOn: '' })

function open(id) {
  if (guard('编辑任务')) return false
  const t = project.tasks.find(x => x.id === id)
  if (!t) return false
  editingId.value = id
  form.name = t.name
  form.owner = String(t.owner)
  form.hours = t.h
  form.weekStart = String(t.w[0])
  form.weekEnd = String(t.w[1])
  form.storyRef = t.story || ''
  form.dependsOn = t.dependsOn || ''
  nextTick(() => { dialogEl.value?.showModal() })
  return true
}
function close() { dialogEl.value?.close() }

/** 引用串规范化:{US|M} 或 T 编号,去重并校验格式 */
function normalizeRefs(value, kind) {
  const refs = taskRefs(value).map(id => id.toUpperCase())
  const pattern = kind === 'US' ? /^US\d+$|^M\d+$/ : /^T\d+$/
  if (refs.length !== new Set(refs).size || refs.some(id => !pattern.test(id))) {
    throw new Error(`请使用 ${kind === 'US' ? 'US' : 'T'} 编号并以逗号分隔`)
  }
  return refs.join(',')
}

async function submit() {
  if (!editingId.value) return
  if (guard('保存任务')) return
  try {
    const weekStart = +form.weekStart, weekEnd = +form.weekEnd
    if (weekStart > weekEnd) throw new Error('开始周不能晚于结束周')
    if (!form.name.trim()) throw new Error('请填写任务名称')
    const storyRef = normalizeRefs(form.storyRef, 'US')
    const dependsOn = normalizeRefs(form.dependsOn, 'T')
    if (taskRefs(dependsOn).includes(editingId.value)) throw new Error('任务不能依赖自身')
    const missingStories = taskRefs(storyRef).filter(id => !project.stories.some(s => s.id === id))
    const missingTasks = taskRefs(dependsOn).filter(id => !project.tasks.some(t => t.id === id))
    if (missingStories.length) throw new Error('关联故事不存在：' + missingStories.join('、'))
    if (missingTasks.length) throw new Error('前置任务不存在：' + missingTasks.join('、'))

    const payload = {
      name: form.name.trim(),
      owner_id: memberUserId(+form.owner),
      hours: +form.hours,
      week_start: weekStart,
      week_end: weekEnd,
      story_ref: storyRef,
      depends_on: dependsOn
    }
    if (session.apiMode) {
      await tasksApi.patch(editingId.value, payload)
      await project.loadAll()
    } else {
      const t = project.tasks.find(x => x.id === editingId.value)
      const keepEh = t.eh == null || t.eh === t.h
      Object.assign(t, {
        name: payload.name, owner: memberIndex(payload.owner_id), h: payload.hours,
        w: [weekStart, weekEnd], story: storyRef, dependsOn,
        sprints: derivedTaskSprints(weekStart, weekEnd)
      })
      if (keepEh) t.eh = payload.hours
      project.addlog('edit', t.id, t.name + ' · 排期 W' + weekStart + '–W' + weekEnd)
      project.checkConsistency()
    }
    close()
    notify('任务已更新' + (session.apiMode ? ' · 已同步到服务器' : ' · 离线演示数据'))
  } catch (err) {
    notify(err.message)
  }
}

defineExpose({ open })
</script>

<template>
  <dialog id="task-editor" ref="dialogEl" aria-labelledby="task-editheading">
    <form id="task-form" @submit.prevent="submit">
      <header>
        <h2 id="task-editheading">任务详情</h2>
        <button type="button" id="task-close" aria-label="关闭" @click="close">×</button>
      </header>
      <div class="formbody">
        <div class="small" id="task-editid">{{ task ? `${task.id} · 任务排期属于执行层，关联 Story：${task.story || '未设置'}` : '' }}</div>
        <label class="field">任务名称<input name="name" v-model="form.name" required maxlength="200"></label>
        <div class="formrow">
          <label class="field">负责人<select name="owner" v-model="form.owner"><option v-for="m in project.members" :key="m.id" :value="String(m.id)">{{ m.name }}</option></select></label>
          <label class="field">预计工时<input name="hours" type="number" min="0" max="999" v-model="form.hours" required></label>
        </div>
        <div class="formrow">
          <label class="field">开始周<select name="weekStart" v-model="form.weekStart"><option v-for="w in 6" :key="w" :value="String(w)">W{{ w }}</option></select></label>
          <label class="field">结束周<select name="weekEnd" v-model="form.weekEnd"><option v-for="w in 6" :key="w" :value="String(w)">W{{ w }}</option></select></label>
        </div>
        <label class="field">关联故事（US 编号，逗号分隔）<input name="storyRef" v-model="form.storyRef" placeholder="例如 US29,US30"></label>
        <label class="field">前置任务（T 编号，逗号分隔）<input name="dependsOn" v-model="form.dependsOn" placeholder="例如 T06,T07"></label>
        <p class="small" style="margin:0">Story 属于需求层；任务日期和负责人属于执行层。调整任务排期不会自动改写 Story Sprint。</p>
        <div class="actions">
          <button type="button" id="task-cancel" @click="close">取消</button>
          <button type="submit" class="primary" :disabled="session.isViewer" :title="session.isViewer ? READONLY_TITLE : ''">保存任务</button>
        </div>
      </div>
    </form>
  </dialog>
</template>
