<script setup>
import { computed, onBeforeUnmount, onMounted } from 'vue'
import { useProjectStore, taskRefs } from '@/stores/project'

const props = defineProps({ taskId: { type: String, required: true } })
const emit = defineEmits(['close'])
const project = useProjectStore()
const task = computed(() => project.taskById(props.taskId))
const stories = computed(() => task.value ? project.storiesForTask(task.value) : [])
const dependencies = computed(() => task.value ? taskRefs(task.value.dependsOn) : [])
function closeOnEscape(event) { if (event.key === 'Escape') emit('close') }
onMounted(() => document.addEventListener('keydown', closeOnEscape))
onBeforeUnmount(() => document.removeEventListener('keydown', closeOnEscape))
</script>

<template>
  <div class="member-drawer task-detail-drawer" role="presentation">
    <div class="drawer-backdrop" @click="emit('close')"></div>
    <aside class="drawer-panel" role="dialog" aria-modal="true" aria-labelledby="task-drawer-title">
      <div class="drawer-head">
        <div>
          <div class="eyebrow">TASK DETAIL / 任务详情</div>
          <h2 id="task-drawer-title">{{ task?.id }} {{ task?.name }}</h2>
        </div>
        <button type="button" class="drawer-close" aria-label="关闭任务详情" @click="emit('close')">×</button>
      </div>
      <div v-if="task" class="member-detail task-detail-content">
        <div class="task-detail-summary">
          <div><b>负责人</b>{{ project.memberName(task.owner) }}</div>
          <div><b>状态</b>{{ project.taskStatusText(task) }}</div>
          <div><b>优先级</b>{{ task.priority || 'Should' }}</div>
          <div><b>Sprint</b>{{ project.taskSprintList(task).join(' / ') }}</div>
          <div><b>计划工时</b>{{ task.h }}h</div>
        </div>
        <section class="task-detail-section">
          <h3>W1-W6 排期</h3>
          <div class="task-week-grid">
            <div v-for="week in 6" :key="week" :class="{ active: task.w[0] <= week && task.w[1] >= week }">W{{ week }}</div>
          </div>
        </section>
        <section class="task-detail-section">
          <h3>关联 Story</h3>
          <p>{{ stories.map(story => story.id + ' ' + story.title).join('、') || '无' }}</p>
        </section>
        <section class="task-detail-section">
          <h3>依赖关系</h3>
          <p>{{ dependencies.join('、') || '无前置任务' }}</p>
        </section>
      </div>
    </aside>
  </div>
</template>
