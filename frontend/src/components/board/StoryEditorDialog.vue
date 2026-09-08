<script setup>
import { ref, reactive, nextTick } from 'vue'
import { useProjectStore } from '@/stores/project'
import { useSessionStore } from '@/stores/session'
import { useToast } from '@/composables/useToast'
import { usePermissionGuard } from '@/composables/usePermissionGuard'
import { storiesApi } from '@/api/stories'
import { tasksApi } from '@/api/tasks'
import { statuses } from '@/constants'
import UndoneChoiceDialog from './UndoneChoiceDialog.vue'

/* 故事编辑弹窗:结构对齐旧版 L692-725,逻辑对齐 openEditor(L934-948)/
   表单提交(L1345-1384)/删除三选一级联(L1386-1434),含跨 Sprint 子任务挪动 */
const props = defineProps({
  inScope: { type: Function, required: true }  // 离线保存后的「当前筛选范围外」提示
})
const project = useProjectStore()
const session = useSessionStore()
const { notify } = useToast()
const { guard } = usePermissionGuard()
const dialogEl = ref(null)
const undoneRef = ref(null)

const editing = ref(null)
const form = reactive({ title: '', description: '', acceptance: '', priority: 'Must', status: '0', owner: '', sprint: '1', activity: '2' })

function open(id = null, status = 0, filters = {}) {
  if (guard('新建或编辑故事')) return
  editing.value = id
  const s = id
    ? project.stories.find(x => x.id === id)
    : {
        title: '', description: '', acceptance: '', priority: 'Must', status,
        owner: filters.owner === 'all' ? null : +filters.owner,
        sprint: filters.sprint === 'all' ? 1 : +filters.sprint,
        activity: 2
      }
  if (!s) return
  for (const k of ['title', 'description', 'acceptance', 'priority', 'status', 'owner', 'sprint', 'activity']) {
    form[k] = s[k] ?? ''
  }
  nextTick(() => { dialogEl.value?.showModal() })
}
function close() { dialogEl.value?.close() }

/* 点击 backdrop 区域关闭(对齐旧版 L947) */
function onDialogClick(e) {
  if (e.target !== dialogEl.value) return
  const r = e.target.getBoundingClientRect()
  if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) e.target.close()
}

async function submit() {
  const payload = {
    title: form.title.trim(), description: form.description.trim(), acceptance: form.acceptance.trim(),
    priority: form.priority, status: +form.status,
    owner_id: form.owner === '' ? null : +form.owner + 1,
    sprint: +form.sprint, activity: +form.activity
  }
  if (!payload.title || !payload.description || !payload.acceptance) { notify('请填写标题、用户故事和验收条件'); return }
  /* 跨 Sprint 变更:默认仅挪动未完成子任务到新时间轴,已完成子任务保留原记录(防误伤历史) */
  if (editing.value) {
    const old = project.stories.find(x => x.id === editing.value)
    if (old && old.sprint !== payload.sprint) {
      const subs = project.subTasksOf(editing.value)
      const undone = subs.filter(t => t.status !== 2)
      if (subs.length) {
        const ok = confirm(`该卡 Sprint 由 S${old.sprint} 变更为 S${payload.sprint}。\n默认仅挪动 ${undone.length} 条未完成子任务到新时间轴,${subs.length - undone.length} 条已完成子任务将保留原记录。确认?`)
        if (ok) {
          const shift = (payload.sprint - old.sprint) * 2
          for (const t of undone) {
            const ws = Math.min(Math.max(t.w[0] + shift, 1), 6)
            const we = Math.min(Math.max(t.w[1] + shift, ws), 6)
            t.w = [ws, we]
            if (session.apiMode) {
              try { await tasksApi.patch(t.id, { week_start: ws, week_end: we }) }
              catch (err) { notify('子任务 ' + t.id + ' 挪动失败:' + err.message) }
            }
          }
        }
      }
    }
  }
  if (session.apiMode) {
    try {
      if (editing.value) await storiesApi.patch(editing.value, payload)
      else await storiesApi.create(payload)
      close()
      await project.loadAll()
      notify((editing.value ? '已更新' : '已创建') + ' · 已同步到服务器')
    } catch (err) { notify(err.message) }
  } else {
    const s = { id: editing.value || 'M' + String(Math.max(23, ...project.stories.map(x => +x.id.slice(1) || 0)) + 1).padStart(2, '0') }
    for (const k of ['title', 'description', 'acceptance', 'priority']) s[k] = payload[k]
    for (const k of ['status', 'sprint', 'activity']) s[k] = payload[k]
    s.owner = form.owner === '' ? null : +form.owner
    if (editing.value) {
      const old = project.stories.find(x => x.id === editing.value)
      project.stories[project.stories.findIndex(x => x.id === editing.value)] = s
      project.addlog('edit', s.id, s.title + (old && old.status !== s.status ? ' · ' + statuses[s.status] : ''))
    } else {
      project.stories.push(s)
      project.addlog('create', s.id, s.title)
    }
    project.persist()
    close()
    notify(`${s.id} 已${editing.value ? '更新' : '创建'}${props.inScope(s.id) ? '' : '（当前筛选范围外）'}`)
  }
}

async function onDelete() {
  if (!editing.value) return
  const t = project.stories.find(x => x.id === editing.value)
  if (!t) return
  const subs = project.subTasksOf(editing.value)
  const undone = subs.filter(x => x.status !== 2 && x.status !== 3)
  let strategy = 'keep'
  if (undone.length) {
    if (!confirm('确认删除 ' + editing.value + '（' + t.title + '）？该卡含未完成子任务,需选择级联策略。')) return
    const choice = await undoneRef.value.open(editing.value, undone.length)
    if (!choice) { notify('已取消删除'); return }
    strategy = choice
  } else {
    if (!confirm('确认删除 ' + editing.value + '（' + t.title + '）？此操作不可撤销。')) return
  }
  if (session.apiMode) {
    try {
      await storiesApi.remove(editing.value, strategy)
      close()
      await project.loadAll()
      notify(editing.value + ' 已删除 · 子任务级联:' + ({ cancel: '标记已取消', keep: '保留跟踪', detach: '转独立任务' })[strategy])
    } catch (err) { notify(err.message) }
  } else {
    /* 离线等效级联:全部子任务解绑(与在线库 FK ON DELETE SET NULL 一致),避免幽灵父行 */
    for (const x of subs) {
      if (strategy === 'cancel' && x.status !== 2 && x.status !== 3) x.status = 3
      x.card = null
      if (strategy === 'detach' && x.status !== 2 && x.status !== 3) x.w = [Math.min(x.w[0] + 2, 6), Math.min(x.w[1] + 2, 6)]
    }
    project.stories = project.stories.filter(x => x.id !== editing.value)
    project.addlog('del', editing.value, t.title + ' · 子任务级联:' + strategy + '(' + undone.length + ' 条未完成)')
    project.persist()
    close()
    notify(editing.value + ' 已删除 · 子任务已解绑:' + ({ cancel: '未完成标取消', keep: '保留跟踪', detach: '转独立任务' })[strategy])
  }
}

defineExpose({ open })
</script>

<template>
  <dialog id="editor" ref="dialogEl" aria-labelledby="editheading" @click="onDialogClick">
    <form id="form" @submit.prevent="submit">
      <header>
        <h2 id="editheading">{{ editing ? '编辑用户故事' : '新建用户故事' }}</h2>
        <button type="button" id="close" aria-label="关闭" @click="close">×</button>
      </header>
      <div class="formbody">
        <div class="small" id="editid">{{ editing ? editing + ' · 修改会同步到看板与故事地图' : '保存后将生成唯一故事编号' }}</div>
        <label class="field">故事标题<input name="title" v-model="form.title" required maxlength="100"></label>
        <label class="field">用户故事<textarea name="description" v-model="form.description" required placeholder="作为…，我希望…，以便…"></textarea></label>
        <div class="formrow">
          <label class="field">状态<select name="status" v-model="form.status"><option value="0">待办</option><option value="1">进行中</option><option value="2">已完成</option></select></label>
          <label class="field">MoSCoW 优先级<select name="priority" v-model="form.priority"><option>Must</option><option>Should</option><option>Could</option></select></label>
        </div>
        <div class="formrow">
          <label class="field">负责人<select name="owner" v-model="form.owner"><option value="">未分配</option><option value="0">成员 1</option><option value="1">成员 2</option><option value="2">成员 3</option><option value="3">成员 4</option></select></label>
          <label class="field">迭代<select name="sprint" v-model="form.sprint"><option value="1">Sprint 1</option><option value="2">Sprint 2</option><option value="3">Sprint 3</option></select></label>
        </div>
        <label class="field">骨干活动<select name="activity" v-model="form.activity">
          <option value="1">A1 进入与组织项目</option>
          <option value="2">A2 梳理需求</option>
          <option value="3">A3 安排与执行</option>
          <option value="4">A4 观察与协同</option>
          <option value="5">A5 复盘改进</option>
        </select></label>
        <label class="field">验收条件<textarea name="acceptance" v-model="form.acceptance" required></textarea></label>
        <div class="actions">
          <button v-if="editing" type="button" id="del" class="danger" style="margin-right:auto" @click="onDelete">删除故事</button>
          <button type="button" id="cancel" @click="close">取消</button>
          <button type="submit" class="primary">保存故事</button>
        </div>
      </div>
    </form>
  </dialog>
  <UndoneChoiceDialog ref="undoneRef" />
</template>
