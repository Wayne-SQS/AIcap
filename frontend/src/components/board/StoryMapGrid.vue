<script setup>
import { computed } from 'vue'
import StoryCard from './StoryCard.vue'
import { activities } from '@/constants'
import { MAP_SPRINTS } from '@/data/seed'

/* 故事地图:横向骨干活动 × 纵向发布切片(含 Sprint 4+「后续路线」)
   切片列增减由 MAP_SPRINTS 决定,卡片沿用 StoryCard(map 模式) */
const props = defineProps({
  data: { type: Array, required: true },
  sprint: { type: String, required: true }
})
const emit = defineEmits(['open'])

const sprintList = computed(() => MAP_SPRINTS.filter(sp =>
  props.sprint === 'all' || (props.sprint === '4plus' ? sp.number === 4 : sp.number === +props.sprint)
))
function cellStories(sp, i) {
  return props.data.filter(s => sp.matches(s) && s.activity === i + 1)
}
</script>

<template>
  <p class="mapnote">横向按骨干活动展开，纵向按 Sprint 切片；点击故事可查看验收条件。Sprint 4+ 为后续路线（US09 / US15 / US21），不纳入本学期六周承诺。</p>
  <div class="mapwrap" tabindex="0" aria-label="可横向滚动的故事地图">
    <div class="mapgrid" id="map">
      <div class="maphead"><span class="map-code">ROADMAP</span><strong>发布切片</strong></div>
      <div v-for="(a, i) in activities" :key="a" class="maphead"><span class="map-code">A{{ i + 1 }}</span><strong>{{ a }}</strong></div>
      <template v-for="sp in sprintList" :key="sp.number">
        <div class="sprinthead" :class="sp.cls">{{ sp.name }}<br><span class="small">{{ sp.tag }}</span></div>
        <div v-for="(a, i) in activities" :key="sp.number + '-' + i" class="mapcell" :class="sp.cls">
          <StoryCard v-for="s in cellStories(sp, i)" :key="s.id" :story="s" :map="true" @open="emit('open', $event)" />
          <span v-if="!cellStories(sp, i).length" class="small">暂无故事</span>
        </div>
      </template>
      <div v-if="!data.length" class="empty" style="grid-column:1/-1">没有匹配的故事，试试调整筛选条件。</div>
    </div>
  </div>
</template>
