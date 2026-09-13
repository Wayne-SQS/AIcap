<script setup>
import { computed } from 'vue'
import { useProjectStore } from '@/stores/project'
import { useSessionStore } from '@/stores/session'
import { READONLY_TITLE } from '@/composables/usePermissionGuard'
import { statuses, activities } from '@/constants'

/* 看板卡片:.card[data-id][draggable] + 子任务工时加权进度徽章 .subprog(血缘口径保留)
   map 模式:去拖拽 + 加 .mapcard 固定卡高 + 状态旁显示 Sprint(对齐 legacy 新版卡片)
   只读账号:卡片(编辑入口)禁用且不可拖拽,保持可见并说明原因 */
const props = defineProps({
  story: { type: Object, required: true },
  map: { type: Boolean, default: false }
})
const emit = defineEmits(['open'])
const project = useProjectStore()
const session = useSessionStore()

const isViewer = computed(() => session.isViewer)
const cardTitle = computed(() => (isViewer.value ? READONLY_TITLE : '验收：' + props.story.acceptance))

const subs = computed(() => project.subTasksOf(props.story.id))
const sp = computed(() => subs.value.length ? Math.round(project.cardPct(props.story) * 100) : null)
const badgeTitle = computed(() => `子任务工时加权进度 ${sp.value}%${subs.value.length ? ` · ${subs.value.filter(t => t.status === 2).length}/${subs.value.length} 条完成` : ''}`)
const ownerName = computed(() => (props.story.owner == null ? '未分配' : project.memberName(props.story.owner)))
</script>

<template>
  <button
    type="button"
    class="card"
    :class="{ mapcard: map }"
    :draggable="!map && !isViewer"
    :disabled="isViewer"
    :data-id="story.id"
    :title="cardTitle"
    :aria-label="'编辑 ' + story.id + ' ' + story.title"
    @click="emit('open', story.id)"
    @dragstart="e => { e.dataTransfer.setData('text/plain', story.id); e.dataTransfer.effectAllowed = 'move' }"
  >
    <div class="cardtop"><span class="id">{{ story.id }}</span><span class="tag" :class="story.priority">{{ story.priority }}</span></div>
    <h3>{{ story.title }}</h3>
    <p class="carddesc">{{ story.description }}</p>
    <p v-if="map" class="mapstatus">{{ statuses[story.status] }} · Sprint {{ story.sprint }}</p>
    <div class="cardfoot">
      <span>A{{ story.activity }} · {{ activities[story.activity - 1] }}</span>
      <span class="owner">
        <span v-if="sp !== null" class="subprog" :class="{ full: sp >= 100 }" :title="badgeTitle">▣ {{ subs.filter(t => t.status === 2).length }}/{{ subs.length }} · {{ sp }}%</span>
        <span class="avatar" :class="'a' + (story.owner ?? 4)">{{ story.owner == null ? '—' : 'P' + (story.owner + 1) }}</span>
        <span>{{ ownerName }}</span>
      </span>
    </div>
  </button>
</template>
