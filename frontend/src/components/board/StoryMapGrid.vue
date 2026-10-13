<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import StoryCard from './StoryCard.vue'
import { DEFAULT_ACTIVITIES } from '@/constants'
import { MAP_SPRINTS } from '@/data/seed'
import { useProjectStore } from '@/stores/project'
import { useSessionStore } from '@/stores/session'
import { legendOf, sortStories, skinOf, SKINS } from '@/data/boardSkins'
import { useCanvasZoom } from '@/composables/useCanvasZoom'
import CanvasZoomControls from '@/components/CanvasZoomControls.vue'

const props = defineProps({
  data: { type: Array, required: true },
  sprints: { type: Array, default: () => MAP_SPRINTS },
  activities: { type: Array, default: () => DEFAULT_ACTIVITIES },
  sprint: { type: String, required: true },
  density: { type: String, default: 'detail' },
  skin: { type: String, default: 'none' },
  sortBy: { type: String, default: 'origin' },
  nowWeek: { type: Number, default: 1 }
})
const emit = defineEmits(['open', 'move'])
const project = useProjectStore()
const session = useSessionStore()
const dragStoryId = ref('')
const dropKey = ref('')
const pointerDrag = ref(null)
const suppressClick = ref(false)
const mapViewport = ref(null)
const mapShell = ref(null)
const mapCanvas = ref(null)
const { zoom, zoomMode, minZoom, maxZoom, canvasStyle, shellStyle, zoomIn, zoomOut, setZoomPercent, resetZoom, applyZoom, measure } =
  useCanvasZoom({ viewport: mapViewport, canvas: mapCanvas, shell: mapShell, fillViewportWidth: true, minZoom: 0.6, fitMinZoom: 0.6, initialMode: 'manual' })

// 画布保持未缩放的布局宽高，transform 只负责显示缩放；shell 承担缩放后的滚动占位。
const mapStyle = computed(() => ({
  ...canvasStyle.value,
  width: activeActivities.value.length > 6 ? `${115 + activeActivities.value.length * 150}px` : canvasStyle.value.width || '100%',
  minWidth: activeActivities.value.length > 6 ? `${115 + activeActivities.value.length * 150}px` : '0',
  maxWidth: activeActivities.value.length > 6 ? `${115 + activeActivities.value.length * 150}px` : undefined,
  gridTemplateColumns: activeActivities.value.length
    ? `115px repeat(${activeActivities.value.length}, minmax(0, 1fr))`
    : '115px'
}))

// 每次点击都读取未受 transform 影响的布局尺寸，按宽高较小值适配并留出 3% 安全边距。
function fitToCanvas() {
  const view = mapViewport.value
  const canvas = mapCanvas.value
  if (!view || !canvas) return
  measure()
  const contentWidth = Math.max(canvas.offsetWidth, canvas.scrollWidth)
  const contentHeight = Math.max(canvas.offsetHeight, canvas.scrollHeight)
  if (!contentWidth || !contentHeight) return
  const scaleX = view.clientWidth / contentWidth
  const scaleY = view.clientHeight / contentHeight
  const fitsAtFullSize = scaleX >= 1 && scaleY >= 1
  const fitScale = fitsAtFullSize ? 1 : Math.max(0.6, Math.min(scaleX, scaleY) * 0.97)
  applyZoom(fitScale, { clientX: view.getBoundingClientRect().left, clientY: view.getBoundingClientRect().top }, 'fit')
  nextTick(() => {
    view.scrollLeft = 0
    view.scrollTop = 0
  })
}

const compact = computed(() => props.density === 'compact')
const activityNames = computed(() => props.activities?.length ? props.activities : DEFAULT_ACTIVITIES)
const activeActivities = computed(() => activityNames.value
  .map((name, index) => ({ name, number: index + 1 }))
  .filter(activity => props.data.some(story => Number(story.activity) === activity.number)))
const ctx = computed(() => ({
  skin: props.skin,
  sprints: props.sprints,
  tasks: project.tasks,
  nowWeek: props.nowWeek,
  members: project.members,
  memberColor: id => project.memberColor(id == null ? 4 : id),
  memberName: id => (id == null ? '未分配' : project.memberName(id))
}))
const skinLabel = computed(() => (SKINS.find(s => s.key === props.skin) || {}).label || '')
const legend = computed(() => legendOf(props.skin, props.data, ctx.value))
const legendSum = computed(() => legend.value.reduce((a, r) => a + r.count, 0))
function cardSkin(story) { return skinOf(story, ctx.value) }
const sprintList = computed(() => props.sprints.filter(sp => props.sprint === 'all' || (props.sprint === '4plus' ? sp.number === 4 : sp.number === +props.sprint)))
// 筛选和密度变化后重新测量，避免沿用上一张地图的滚动范围。
watch([activeActivities, sprintList, compact], () => {
  measure()
  if (zoomMode.value === 'fit') fitToCanvas()
}, { flush: 'post' })
function cellStories(sp, activity) { return sortStories(props.data.filter(s => sp.matches(s) && Number(s.activity) === activity.number), props.sortBy, ctx.value) }
function keyOf(sp, activity) { return `${sp.number}-${activity}` }
function startDrag(id) { dragStoryId.value = id }
function clearDrag() { dragStoryId.value = ''; dropKey.value = '' }
function emitMove(storyId, sprint, activity) {
  if (!storyId || session.isViewer) return
  emit('move', { storyId, id: storyId, sprint, activity })
}
function drop(sp, activity, event) {
  const storyId = dragStoryId.value || event.dataTransfer?.getData('text/plain')
  clearDrag()
  emitMove(storyId, sp.number, activity)
}
function startPointer(id, event) {
  if (session.isViewer || event.button !== 0) return
  pointerDrag.value = { id, startX: event.clientX, startY: event.clientY, moved: false }
  document.addEventListener('pointermove', movePointer)
  document.addEventListener('pointerup', endPointer, { once: true })
}
function startPointerFromGrid(event) {
  const card = event.target.closest('.card[data-id]')
  if (card) startPointer(card.dataset.id, event)
}
function movePointer(event) {
  const drag = pointerDrag.value
  if (!drag) return
  drag.moved ||= Math.hypot(event.clientX - drag.startX, event.clientY - drag.startY) > 5
  if (!drag.moved) return
  dragStoryId.value = drag.id
  const cell = document.elementFromPoint(event.clientX, event.clientY)?.closest('[data-map-drop]')
  dropKey.value = cell?.dataset.mapDrop || ''
}
function endPointer() {
  document.removeEventListener('pointermove', movePointer)
  const drag = pointerDrag.value
  const target = dropKey.value
  pointerDrag.value = null
  clearDrag()
  if (!drag?.moved || !target) return
  const [sprint, activity] = target.split('-').map(Number)
  suppressClick.value = true
  emitMove(drag.id, sprint, activity)
  setTimeout(() => { suppressClick.value = false }, 0)
}
function captureClick(event) {
  if (suppressClick.value) { event.preventDefault(); event.stopPropagation() }
}
onBeforeUnmount(() => document.removeEventListener('pointermove', movePointer))
</script>

<template>
  <p class="mapnote">横向按骨干活动展开，纵向按 Sprint 切片；Sprint 作为故事筛选和辅助信息。点击故事可查看验收条件。</p>
  <div v-if="legend.length" class="maplegend" id="maplegend" :data-skin="skin">
    <span class="legendtitle">马甲 · {{ skinLabel }}</span>
    <span v-for="r in legend" :key="r.glyph" class="legenditem" :class="r.tone" :data-key="r.glyph" :data-count="r.count">
      <i class="legendmark" aria-hidden="true">{{ r.glyph }}</i><span>{{ r.label }}</span>
      <span v-if="r.detail" class="legenddetail">{{ r.detail }}</span><b>{{ r.count }}</b>
    </span>
    <span class="legendsum small" id="legendsum">合计 {{ legendSum }} / 当前筛选 {{ data.length }}</span>
  </div>
  <CanvasZoomControls :zoom="zoom" :min-zoom="minZoom" :max-zoom="maxZoom" :zoom-mode="zoomMode"
    :reset-zoom="resetZoom" :fit-to-canvas="fitToCanvas" :zoom-in="zoomIn" :zoom-out="zoomOut" :set-zoom-percent="setZoomPercent" />
  <div ref="mapViewport" class="mapwrap canvas-zoom-viewport" tabindex="0" aria-label="可横向滚动的故事地图" @click.capture="captureClick">
    <div ref="mapShell" class="canvas-zoom-shell" :style="shellStyle">
      <div ref="mapCanvas" class="mapgrid" id="map" :class="{ compact, skinned: skin !== 'none' }"
        :style="mapStyle" @pointerdown.capture="startPointerFromGrid">
        <div class="maphead" style="grid-column:1;grid-row:1"><span class="map-code">ROADMAP</span><strong>发布切片</strong></div>
        <div v-for="(activity, i) in activeActivities" :key="activity.number" class="maphead"
          :style="{ gridColumn: i + 2, gridRow: 1 }"><span class="map-code">A{{ activity.number }}</span><strong>{{ activity.name }}</strong></div>
        <template v-for="(sp, rowIndex) in sprintList" :key="sp.number">
          <div class="sprinthead" :class="sp.cls"
            :style="{ gridColumn: 1, gridRow: rowIndex + 2 }">{{ sp.name }}<br><span class="small">{{ sp.tag }}</span></div>
          <div v-for="(activity, i) in activeActivities" :key="sp.number + '-' + activity.number"
            class="mapcell" :class="[sp.cls, { compact, 'is-drop-target': dropKey === keyOf(sp, i + 1) }]"
            :style="{ gridColumn: i + 2, gridRow: rowIndex + 2 }"
            :data-cell="sp.number + '-' + activity.number" :data-map-drop="keyOf(sp, activity.number)"
            @dragenter.prevent="dropKey = keyOf(sp, activity.number)" @dragover.prevent="dropKey = keyOf(sp, activity.number)"
            @dragleave.self="dropKey = ''" @drop.prevent="drop(sp, activity.number, $event)">
            <StoryCard v-for="story in cellStories(sp, activity)" :key="story.id" :story="story" :map="true"
              :compact="compact" :skin="cardSkin(story)" :class="{ 'is-dragging': dragStoryId === story.id }"
              @open="emit('open', $event)" @dragstart="startDrag(story.id)" @dragend="clearDrag" />
            <span v-if="!cellStories(sp, activity).length" class="small">暂无故事</span>
          </div>
        </template>
        <div v-if="!data.length" class="empty" style="grid-column:1/-1">没有匹配的故事，试试调整筛选条件。</div>
      </div>
    </div>
  </div>
</template>
