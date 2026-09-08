<script setup>
import { computed } from 'vue'
import StoryCard from './StoryCard.vue'
import { activities } from '@/constants'
import { SPRINTS } from '@/data/seed'

/* 故事地图:横向骨干活动 × 纵向 Sprint 切片,对齐旧版 render() 中 #map 部分(L931-932) */
const props = defineProps({
  data: { type: Array, required: true },
  sprint: { type: String, required: true }
})
const emit = defineEmits(['open'])

const sprintList = computed(() => [1, 2, 3].filter(n => props.sprint === 'all' || +props.sprint === n))
function cellStories(n, i) {
  return props.data.filter(s => s.sprint === n && s.activity === i + 1)
}
</script>

<template>
  <p class="mapnote">横向按骨干活动展开，纵向按 Sprint 切片；点击故事可查看验收条件。</p>
  <div class="mapwrap" tabindex="0" aria-label="可横向滚动的故事地图">
    <div class="mapgrid" id="map">
      <div class="maphead">发布切片</div>
      <div v-for="(a, i) in activities" :key="a" class="maphead"><span class="mono small">A{{ i + 1 }}</span><br>{{ a }}</div>
      <template v-for="n in sprintList" :key="n">
        <div class="sprinthead">SPRINT {{ n }}<br><span class="small">{{ SPRINTS[n - 1].tag }}</span></div>
        <div v-for="(a, i) in activities" :key="n + '-' + i" class="mapcell">
          <StoryCard v-for="s in cellStories(n, i)" :key="s.id" :story="s" :map="true" @open="emit('open', $event)" />
          <span v-if="!cellStories(n, i).length" class="small">—</span>
        </div>
      </template>
      <div v-if="!data.length" class="empty" style="grid-column:1/-1">没有匹配的故事，试试调整筛选条件。</div>
    </div>
  </div>
</template>
