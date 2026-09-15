<script setup>
import { computed } from 'vue'
import StoryCard from './StoryCard.vue'
import { activities } from '@/constants'
import { MAP_SPRINTS } from '@/data/seed'
import { useProjectStore } from '@/stores/project'
import { legendOf, sortStories, skinOf, SKINS } from '@/data/boardSkins'

/* 故事地图:横向骨干活动 × 纵向发布切片(含 Sprint 4+「后续路线」)
   切片列增减由 MAP_SPRINTS 决定,卡片沿用 StoryCard(map 模式)

   两个正交的呈现开关(纯展示,不改动任何数据):
     density  详细(.mapcard 固定 148px 高) / 总览(.chip 单行 22px,格内多列换行)
              —— 详细态整图约 2600px(需要滚三屏),总览态约 500px,四个切片一屏看全,
                 这样"切片分布"这个故事地图唯一不可替代的信息才读得出来。
     skin     马甲图层(单选,禁止叠加,配常驻图例):紧急程度 / 负责人 / Sprint / 无着色
     sortBy   格内排序:原次序 / 紧急程度 / 负责人

   nowWeek 由 BoardView 注入 —— 全应用只有 data/planCalendar.js 一处定义"现在",
   本组件不做任何时间推断,也就没有第二套口径。 */
const props = defineProps({
  data: { type: Array, required: true },
  sprint: { type: String, required: true },
  density: { type: String, default: 'detail' },
  skin: { type: String, default: 'none' },
  sortBy: { type: String, default: 'origin' },
  nowWeek: { type: Number, default: 1 }
})
const emit = defineEmits(['open'])
const project = useProjectStore()

const compact = computed(() => props.density === 'compact')

/* 马甲计算的注入上下文:成员色/姓名来自 store,其余来自本组件 —— boardSkins 保持纯函数 */
const ctx = computed(() => ({
  skin: props.skin,
  tasks: project.tasks,
  nowWeek: props.nowWeek,
  members: project.members,
  memberColor: id => project.memberColor(id == null ? 4 : id),
  memberName: id => (id == null ? '未分配' : project.memberName(id))
}))
const skinLabel = computed(() => (SKINS.find(s => s.key === props.skin) || {}).label || '')
const legend = computed(() => legendOf(props.skin, props.data, ctx.value))
const legendSum = computed(() => legend.value.reduce((a, r) => a + r.count, 0))
function cardSkin(s) { return skinOf(s, ctx.value) }

const sprintList = computed(() => MAP_SPRINTS.filter(sp =>
  props.sprint === 'all' || (props.sprint === '4plus' ? sp.number === 4 : sp.number === +props.sprint)
))
function cellStories(sp, i) {
  return sortStories(props.data.filter(s => sp.matches(s) && s.activity === i + 1), props.sortBy, ctx.value)
}
</script>

<template>
  <p class="mapnote">横向按骨干活动展开，纵向按 Sprint 切片；点击故事可查看验收条件。Sprint 4+ 为后续路线，不纳入本学期六周承诺。</p>

  <!-- 常驻图例:每个色块都带字符标记 + 实时计数,计数之和恒等于当前筛选范围(可相加校验) -->
  <div v-if="legend.length" class="maplegend" id="maplegend" :data-skin="skin">
    <span class="legendtitle">马甲 · {{ skinLabel }}</span>
    <span
      v-for="r in legend" :key="r.glyph" class="legenditem" :class="r.tone"
      :data-key="r.glyph" :data-count="r.count"
    >
      <i class="legendmark" aria-hidden="true">{{ r.glyph }}</i>
      <span>{{ r.label }}</span>
      <span v-if="r.detail" class="legenddetail">{{ r.detail }}</span>
      <b>{{ r.count }}</b>
    </span>
    <span class="legendsum small" id="legendsum">合计 {{ legendSum }} / 当前筛选 {{ data.length }}</span>
  </div>

  <div class="mapwrap" tabindex="0" aria-label="可横向滚动的故事地图">
    <div class="mapgrid" id="map" :class="{ compact, skinned: skin !== 'none' }">
      <div class="maphead"><span class="map-code">ROADMAP</span><strong>发布切片</strong></div>
      <div v-for="(a, i) in activities" :key="a" class="maphead"><span class="map-code">A{{ i + 1 }}</span><strong>{{ a }}</strong></div>
      <template v-for="sp in sprintList" :key="sp.number">
        <div class="sprinthead" :class="sp.cls">{{ sp.name }}<br><span class="small">{{ sp.tag }}</span></div>
        <div v-for="(a, i) in activities" :key="sp.number + '-' + i" class="mapcell" :class="[sp.cls, { compact }]">
          <StoryCard
            v-for="s in cellStories(sp, i)" :key="s.id" :story="s"
            :map="true" :compact="compact" :skin="cardSkin(s)"
            @open="emit('open', $event)"
          />
          <span v-if="!cellStories(sp, i).length" class="small">暂无故事</span>
        </div>
      </template>
      <div v-if="!data.length" class="empty" style="grid-column:1/-1">没有匹配的故事，试试调整筛选条件。</div>
    </div>
  </div>
</template>
