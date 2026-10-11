<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import StoryCard from './StoryCard.vue'
import { activities } from '@/constants'
import { MAP_SPRINTS } from '@/data/seed'
import { useProjectStore } from '@/stores/project'
import { useSessionStore } from '@/stores/session'
import { legendOf, sortStories, skinOf, SKINS } from '@/data/boardSkins'
import { useCanvasZoom } from '@/composables/useCanvasZoom'
import CanvasZoomControls from '@/components/CanvasZoomControls.vue'

const props = defineProps({
  data: { type: Array, required: true },
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
const { zoom, zoomMode, minZoom, maxZoom, canvasStyle, shellStyle, zoomIn, zoomOut, setZoomPercent, resetZoom, fitToCanvas } =
  useCanvasZoom({ viewport: mapViewport, canvas: mapCanvas, shell: mapShell, fillViewportWidth: true, minContentWidth: 968, fitMinZoom: 0.2, initialMode: 'manual' })

const compact = computed(() => props.density === 'compact')
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
function cardSkin(story) { return skinOf(story, ctx.value) }
const sprintList = computed(() => MAP_SPRINTS.filter(sp => props.sprint === 'all' || (props.sprint === '4plus' ? sp.number === 4 : sp.number === +props.sprint)))
function cellStories(sp, i) { return sortStories(props.data.filter(s => sp.matches(s) && s.activity === i + 1), props.sortBy, ctx.value) }
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
  <p class="mapnote">横向按骨干活动展开，纵向按 Sprint 切片；点击故事可查看验收条件。Sprint 4+ 为后续路线，不纳入本学期六周承诺。</p>
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
      <div ref="mapCanvas" class="mapgrid" id="map" :class="{ compact, skinned: skin !== 'none' }" :style="canvasStyle" @pointerdown.capture="startPointerFromGrid">
        <div class="maphead"><span class="map-code">ROADMAP</span><strong>发布切片</strong></div>
        <div v-for="(a, i) in activities" :key="a" class="maphead"><span class="map-code">A{{ i + 1 }}</span><strong>{{ a }}</strong></div>
        <template v-for="sp in sprintList" :key="sp.number">
          <div class="sprinthead" :class="sp.cls">{{ sp.name }}<br><span class="small">{{ sp.tag }}</span></div>
          <div v-for="(a, i) in activities" :key="sp.number + '-' + i"
            class="mapcell" :class="[sp.cls, { compact, 'is-drop-target': dropKey === keyOf(sp, i + 1) }]"
            :data-cell="sp.number + '-' + (i + 1)" :data-map-drop="`${sp.number}-${i + 1}`"
            @dragenter.prevent="dropKey = keyOf(sp, i + 1)" @dragover.prevent="dropKey = keyOf(sp, i + 1)"
            @dragleave.self="dropKey = ''" @drop.prevent="drop(sp, i + 1, $event)">
            <StoryCard v-for="story in cellStories(sp, i)" :key="story.id" :story="story" :map="true"
              :compact="compact" :skin="cardSkin(story)" :class="{ 'is-dragging': dragStoryId === story.id }"
              @open="emit('open', $event)" @dragstart="startDrag(story.id)" @dragend="clearDrag" />
            <span v-if="!cellStories(sp, i).length" class="small">暂无故事</span>
          </div>
        </template>
        <div v-if="!data.length" class="empty" style="grid-column:1/-1">没有匹配的故事，试试调整筛选条件。</div>
      </div>
    </div>
  </div>
</template>
