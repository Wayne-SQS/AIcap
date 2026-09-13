<script setup>
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { useProjectStore, taskRefs } from '@/stores/project'

/* 成员任务明细抽屉(移植 legacy 新版 #member-drawer,交互按 Vue 重写)
   关闭方式:关闭按钮 / 点击遮罩 / Esc;筛选状态在抽屉内部维护,不污染页面其它视图 */
const props = defineProps({
  memberId: { type: Number, required: true }
})
const emit = defineEmits(['close'])
const project = useProjectStore()

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
onMounted(() => document.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div class="member-drawer" id="member-drawer" role="presentation">
    <div class="drawer-backdrop" data-drawer-close @click="emit('close')"></div>
    <aside class="drawer-panel" role="dialog" aria-modal="true" aria-labelledby="drawer-title">
      <div class="drawer-head">
        <h2 id="drawer-title">P{{ memberId + 1 }} {{ member?.name }} · 任务明细</h2>
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
        <div class="task-list">
          <div v-for="t in filtered" :key="t.id" class="task-item">
            <span class="task-key">{{ t.id }}</span>
            <div class="task-summary">
              <b :title="t.name">{{ t.name }}</b>
              <div class="task-meta">{{ storyRefText(t) }} · Sprint {{ project.taskSprintList(t).join('/') }} · W{{ t.w[0] }}–W{{ t.w[1] }} · {{ t.h }}h · 进度 {{ t.progress || 0 }}%</div>
            </div>
            <span class="task-state" :class="project.taskStatusKey(t)">{{ project.taskStatusText(t) }}</span>
          </div>
          <div v-if="!filtered.length" class="empty">该筛选条件下暂无任务。</div>
        </div>
      </div>
    </aside>
  </div>
</template>
