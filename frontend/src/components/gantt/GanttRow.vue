<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
/* 甘特图行:父行(parent)/子行(child)/管理行(mgmt)/分组标题(group) 四态
   bar 为可点击按钮(selection 由父组件持有,点击只上报 key);
   meta 为行内任务细节段(ID/负责人/工时/Sprint/关联 Story/看板卡) */
const props = defineProps({
  variant: { type: String, default: 'child' },  // parent | child | mgmt | group
  title: { type: String, required: true },
  sub: { type: String, default: '' },
  meta: { type: Array, default: () => [] },     // [{ text, cls?, title? }]
  /* bar: { left, width, title, cls, label, pct, key, selected, relation } */
  bar: { type: Object, default: null },
  clickKey: { type: String, default: '' },
  leftSelected: { type: Boolean, default: false },
  draggable: { type: Boolean, default: false },
  saving: { type: Boolean, default: false }
})
const emit = defineEmits(['select', 'schedule-change'])
const drag = ref(null)
const previewDelta = ref(0)
const moved = ref(false)

const barStyle = computed(() => {
  if (!props.bar) return {}
  let left = Number(props.bar.left), width = Number(props.bar.width)
  if (drag.value) {
    const step = 100 / 6
    if (drag.value.mode === 'move') left += previewDelta.value * step
    if (drag.value.mode === 'resize-start') { left += previewDelta.value * step; width -= previewDelta.value * step }
    if (drag.value.mode === 'resize-end') width += previewDelta.value * step
  }
  return { left: left + '%', width: width + '%' }
})

function beginDrag(event, mode) {
  if (!props.draggable || event.button !== 0) return
  const track = event.currentTarget.closest('.gtrack')
  const target = event.currentTarget
  drag.value = { mode, startX: event.clientX, weekWidth: track.getBoundingClientRect().width / 6, target, pointerId: event.pointerId }
  previewDelta.value = 0
  moved.value = false
  target.setPointerCapture?.(event.pointerId)
  target.addEventListener('pointermove', onPointerMove)
  target.addEventListener('pointerup', endDrag, { once: true })
  event.preventDefault()
}
function onPointerMove(event) {
  if (!drag.value) return
  previewDelta.value = Math.round((event.clientX - drag.value.startX) / drag.value.weekWidth)
  moved.value ||= Math.abs(event.clientX - drag.value.startX) > 4
}
function endDrag() {
  const current = drag.value
  current?.target.removeEventListener('pointermove', onPointerMove)
  current?.target.releasePointerCapture?.(current.pointerId)
  const deltaWeeks = previewDelta.value
  drag.value = null
  previewDelta.value = 0
  if (current && moved.value && deltaWeeks) emit('schedule-change', { key: props.bar.key, mode: current.mode, deltaWeeks })
  setTimeout(() => { moved.value = false }, 0)
}
function selectBar() {
  if (!moved.value) emit('select', props.bar.key)
}
function selectLeft() {
  if (props.clickKey) emit('select', props.clickKey)
}
onBeforeUnmount(() => drag.value?.target.removeEventListener('pointermove', onPointerMove))
</script>

<template>
  <div class="grow" :class="[variant, { selected: (bar && bar.selected) || leftSelected }]">
    <div class="tname" :class="{ 'is-clickable': clickKey }" @click="selectLeft">
      <b :title="title">{{ title }}</b>
      <small v-if="sub">{{ sub }}</small>
      <div v-if="meta.length" class="taskmeta">
        <span v-for="(m, i) in meta" :key="i" :class="m.cls" :title="m.title">{{ m.text }}</span>
      </div>
    </div>
    <div class="gtrack">
      <button
        v-if="bar"
        type="button"
        class="gbar"
        :class="[bar.cls, bar.relation || '', { selected: bar.selected, 'is-draggable': draggable, 'is-dragging': !!drag, 'is-saving': saving }]"
        :data-gantt-task="bar.key"
        :style="barStyle"
        :title="bar.title"
        @click="selectBar"
        @pointerdown="beginDrag($event, 'move')"
      >
        <span v-if="draggable" class="g-resize start" title="调整开始周" @pointerdown.stop="beginDrag($event, 'resize-start')"></span>
        <i v-if="bar.pct != null" class="pfill" :style="{ width: bar.pct + '%' }"></i>
        <span style="position:relative;z-index:1">{{ bar.label }}</span>
        <span v-if="draggable" class="g-resize end" title="调整结束周" @pointerdown.stop="beginDrag($event, 'resize-end')"></span>
      </button>
      <div class="ggrid"><div v-for="w in 6" :key="w" class="gcell"></div></div>
    </div>
  </div>
</template>
