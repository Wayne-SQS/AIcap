<script setup>
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { useProjectStore, taskRefs } from '@/stores/project'
import { useSessionStore } from '@/stores/session'
import { useAgentRefresh, completingTaskId } from '@/composables/useAgentRefresh'
import { READONLY_TITLE } from '@/composables/usePermissionGuard'
import { memberLabel } from '@/data/memberIdentity'

/* 成员任务明细抽屉(移植 legacy 新版 #member-drawer,交互按 Vue 重写)
   关闭方式:关闭按钮 / 点击遮罩 / Esc;筛选状态在抽屉内部维护,不污染页面其它视图

   这里是「谁提交了 → 到自己任务后面点已完成 → 触发智能体」的落点:
   任务行的状态原先只是只读文字,成员没有任何入口把任务置为已完成。 */
const props = defineProps({
  memberId: { type: Number, required: true }
})
const emit = defineEmits(['close', 'reassign'])
const project = useProjectStore()
const session = useSessionStore()
const { completeTask } = useAgentRefresh()
const doneTitle = computed(() => (session.isViewer ? READONLY_TITLE : '标记完成并触发任务提交智能体'))
const dragTaskId = ref('')
const dropMemberId = ref(null)
const pointerDrag = ref(null)

const filter = ref('all')
const FILTERS = [['all', '全部'], ['todo', '待办'], ['doing', '进行中'], ['done', '已完成'], ['blocked', '阻塞']]

const member = computed(() => project.memberById(props.memberId))
const mine = computed(() => project.memberTasks(props.memberId))
const filtered = computed(() => (filter.value === 'all' ? mine.value : mine.value.filter(t => project.taskStatusKey(t) === filter.value)))
const unfinished = computed(() => mine.value.filter(t => project.taskStatusKey(t) !== 'done').length)

function storyRefText(t) {
  return taskRefs(t.story).join('、') || '无'
}
function onKeydown(e) {
  if (e.key === 'Escape') emit('close')
}
function startTaskDrag(task, event) {
  if (session.isViewer) return
  dragTaskId.value = task.id
  event.dataTransfer.setData('application/x-aiguanli-task', task.id)
  event.dataTransfer.setData('text/plain', task.id)
  event.dataTransfer.effectAllowed = 'move'
}
function clearDrag() { dragTaskId.value = ''; dropMemberId.value = null }
function dropTo(ownerId, event) {
  const taskId = dragTaskId.value || event.dataTransfer?.getData('application/x-aiguanli-task')
  clearDrag()
  if (taskId && !session.isViewer) emit('reassign', { taskId, ownerId })
}
function selectOwner(task, event) {
  const ownerId = Number(event.target.value)
  event.target.value = String(task.owner)
  emit('reassign', { taskId: task.id, ownerId })
}
function startPointer(task, event) {
  if (session.isViewer || event.button !== 0 || event.target.closest('select')) return
  pointerDrag.value = { taskId: task.id, startX: event.clientX, startY: event.clientY, moved: false }
  document.addEventListener('pointermove', movePointer)
  document.addEventListener('pointerup', endPointer, { once: true })
}
function movePointer(event) {
  const drag = pointerDrag.value
  if (!drag) return
  drag.moved ||= Math.hypot(event.clientX - drag.startX, event.clientY - drag.startY) > 5
  if (!drag.moved) return
  dragTaskId.value = drag.taskId
  const zone = document.elementFromPoint(event.clientX, event.clientY)?.closest('[data-owner-drop]')
  dropMemberId.value = zone ? Number(zone.dataset.ownerDrop) : null
}
function endPointer() {
  document.removeEventListener('pointermove', movePointer)
  const drag = pointerDrag.value
  const ownerId = dropMemberId.value
  pointerDrag.value = null
  clearDrag()
  if (drag?.moved && ownerId !== null) emit('reassign', { taskId: drag.taskId, ownerId })
}
onMounted(() => document.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown)
  document.removeEventListener('pointermove', movePointer)
})
</script>

<template>
  <div class="member-drawer" id="member-drawer" role="presentation">
    <div class="drawer-backdrop" data-drawer-close @click="emit('close')"></div>
    <aside class="drawer-panel" role="dialog" aria-modal="true" aria-labelledby="drawer-title">
      <div class="drawer-head">
        <h2 id="drawer-title">{{ memberLabel(memberId) }} {{ member?.name }} · 任务明细</h2>
        <span class="small">任务执行视图</span>
        <button type="button" class="drawer-close" data-drawer-close aria-label="关闭成员任务明细" @click="emit('close')">×</button>
      </div>
      <div class="member-detail" id="member-detail">
        <div class="member-detail-head">
          <div>
            <b>{{ project.memberRoleText(memberId) }}</b>
            <span class="small"> · 总任务 {{ mine.length }} · 未完成 {{ unfinished }} · 容量 {{ project.capacityHours(memberId) }}h</span>
          </div>
        </div>
        <div class="member-summary">
          <div><b>可分配工时</b>{{ project.allocatedHours(memberId) }}h / {{ project.capacityHours(memberId) }}h</div>
          <div><b>负载档位</b>{{ project.capacityStatus(project.utilization(memberId)).label }}</div>
        </div>
        <div class="member-detail-head">
          <div class="member-filter">
            <button
              v-for="[key, label] in FILTERS" :key="key" type="button"
              :class="{ active: filter === key }" :data-member-filter="key" @click="filter = key"
            >{{ label }}</button>
          </div>
        </div>
        <div class="task-owner-zones" aria-label="任务负责人放置区域">
          <div class="small">拖动任务到成员，或使用任务行中的负责人下拉框</div>
          <div class="owner-zone-grid">
            <div
              v-for="m in project.members" :key="m.id" class="owner-drop-zone"
              :class="{ current: m.id === memberId, 'is-drop-target': dropMemberId === m.id }"
              :data-owner-drop="m.id"
              @dragenter.prevent="dropMemberId = m.id" @dragover.prevent="dropMemberId = m.id"
              @dragleave.self="dropMemberId = null" @drop.prevent="dropTo(m.id, $event)"
            ><span class="avatar" :class="'a' + m.id">P{{ m.id + 1 }}</span><span>{{ m.name }}</span></div>
          </div>
        </div>
        <div class="task-list">
          <div
            v-for="t in filtered" :key="t.id" class="task-item"
            :class="{ 'is-dragging': dragTaskId === t.id }"
            :draggable="!session.isViewer" :title="session.isViewer ? READONLY_TITLE : '拖动以调整负责人'"
            @dragstart="startTaskDrag(t, $event)" @dragend="clearDrag"
            @pointerdown="startPointer(t, $event)"
          >
            <span class="task-key">{{ t.id }}</span>
            <div class="task-summary">
              <b :title="t.name">{{ t.name }}</b>
              <div class="task-meta">{{ storyRefText(t) }} · {{ t.priority || 'Should' }} · Sprint {{ project.taskSprintList(t).join('/') }} · W{{ t.w[0] }}–W{{ t.w[1] }} · {{ t.h }}h · 进度 {{ t.progress || 0 }}%</div>
            </div>
            <div class="task-item-actions">
              <select :value="String(t.owner)" :disabled="session.isViewer" :title="session.isViewer ? READONLY_TITLE : '修改负责人'" @change="selectOwner(t, $event)">
                <option v-for="m in project.members" :key="m.id" :value="String(m.id)">{{ m.name }}</option>
              </select>
            </div>
            <span class="task-tail">
              <span class="task-state" :class="project.taskStatusKey(t)">{{ project.taskStatusText(t) }}</span>
              <button
                v-if="project.taskStatusKey(t) !== 'done'"
                type="button"
                class="task-done"
                :data-task-done="t.id"
                :disabled="session.isViewer || completingTaskId === t.id"
                :title="doneTitle"
                @click="completeTask(t)"
              >{{ completingTaskId === t.id ? '提交中…' : '✓ 已完成' }}</button>
            </span>
          </div>
          <div v-if="!filtered.length" class="empty">该筛选条件下暂无任务。</div>
        </div>
      </div>
    </aside>
  </div>
</template>
