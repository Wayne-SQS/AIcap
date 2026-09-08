<script setup>
import { computed } from 'vue'
import { useProjectStore } from '@/stores/project'
import { statuses, activities, esc } from '@/constants'

/* 看板卡片:结构逐字对齐旧版 card()(L914-918)
   .card[data-id][draggable] + 子任务进度徽章 .subprog + 头像,map 模式去拖拽 */
const props = defineProps({
  story: { type: Object, required: true },
  map: { type: Boolean, default: false }
})
const emit = defineEmits(['open'])
const project = useProjectStore()

const subs = computed(() => project.subTasksOf(props.story.id))
const sp = computed(() => subs.value.length ? Math.round(project.cardPct(props.story) * 100) : null)
const badgeTitle = computed(() => `子任务工时加权进度 ${sp.value}%${subs.value.length ? ` · ${subs.value.filter(t => t.status === 2).length}/${subs.value.length} 条完成` : ''}`)
</script>

<template>
  <button
    type="button"
    class="card"
    :draggable="!map"
    :data-id="story.id"
    :title="'验收：' + story.acceptance"
    :aria-label="'编辑 ' + story.id + ' ' + story.title"
    @click="emit('open', story.id)"
    @dragstart="e => { e.dataTransfer.setData('text/plain', story.id); e.dataTransfer.effectAllowed = 'move' }"
  >
    <div class="cardtop"><span class="id">{{ story.id }}</span><span class="tag" :class="story.priority">{{ story.priority }}</span></div>
    <h3>{{ story.title }}</h3>
    <p class="carddesc">{{ story.description }}</p>
    <p v-if="map" class="mapstatus">{{ statuses[story.status] }}</p>
    <div class="cardfoot">
      <span>A{{ story.activity }} · {{ activities[story.activity - 1] }}</span>
      <span class="owner">
        <span v-if="sp !== null" class="subprog" :class="{ full: sp >= 100 }" :title="badgeTitle">▣ {{ subs.filter(t => t.status === 2).length }}/{{ subs.length }} · {{ sp }}%</span>
        <span class="avatar" :class="'a' + story.owner">{{ story.owner == null ? '—' : 'P' + (story.owner + 1) }}</span>
        <span>{{ story.owner == null ? '未分配' : '成员 ' + (story.owner + 1) }}</span>
      </span>
    </div>
  </button>
</template>
