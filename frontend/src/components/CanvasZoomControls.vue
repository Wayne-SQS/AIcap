<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  zoom: { type: Number, required: true },
  minZoom: { type: Number, required: true },
  maxZoom: { type: Number, required: true },
  zoomMode: { type: String, required: true },
  resetZoom: { type: Function, required: true },
  fitToCanvas: { type: Function, required: true },
  zoomIn: { type: Function, required: true },
  zoomOut: { type: Function, required: true },
  setZoomPercent: { type: Function, required: true }
})

const input = ref(Math.round(props.zoom * 100))
const minPercent = computed(() => Math.round(props.minZoom * 100))
const maxPercent = computed(() => Math.round(props.maxZoom * 100))
const inputMinPercent = computed(() => Math.min(minPercent.value, Math.round(props.zoom * 100)))

watch(() => props.zoom, value => { input.value = Math.round(value * 100) })

function commit() {
  const value = Number(input.value)
  input.value = Number.isFinite(value) && value > 0
    ? props.setZoomPercent(value)
    : Math.round(props.zoom * 100)
}
</script>

<template>
  <div class="canvas-zoom-toolbar" :aria-label="'画布缩放控制'">
    <button type="button" title="恢复 100%" @click="props.resetZoom">100%</button>
    <button type="button" title="适应画布" @click="props.fitToCanvas">智能适应画布</button>
    <span class="canvas-zoom-spacer" aria-hidden="true"></span>
    <button type="button" title="放大 10%" :disabled="props.zoom >= props.maxZoom" @click="props.zoomIn">+</button>
    <input
      v-model="input"
      class="canvas-zoom-input"
      type="number"
      inputmode="numeric"
      :min="inputMinPercent"
      :max="maxPercent"
      step="1"
      aria-label="缩放百分比"
      @keydown.enter.prevent="commit"
      @blur="commit"
    >
    <span class="canvas-zoom-percent">%</span>
    <button type="button" title="缩小 10%" :disabled="props.zoom <= props.minZoom" @click="props.zoomOut">-</button>
  </div>
</template>
