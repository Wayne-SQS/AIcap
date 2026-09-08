<script setup>
import { computed } from 'vue'

/* 看板统计条:文案/结构对齐旧版 L498-506 + render()(L920-926) */
const props = defineProps({
  data: { type: Array, required: true },
  totalStories: { type: Number, required: true }
})
const total = computed(() => String(props.data.length).padStart(2, '0'))
const doing = computed(() => String(props.data.filter(s => s.status === 1).length).padStart(2, '0'))
const done = computed(() => props.data.filter(s => s.status === 2).length)
const pct = computed(() => props.data.length ? Math.round(done.value / props.data.length * 100) : 0)
</script>

<template>
  <section class="summary" aria-label="统计">
    <div class="stat"><label>当前范围 · 故事总数</label><div><strong id="total">{{ total }}</strong><span class="small"> 个故事</span></div></div>
    <div class="stat"><label>正在推进</label><div><strong id="doing">{{ doing }}</strong><span class="small"> 个进行中</span></div></div>
    <div class="stat">
      <div class="inline"><label style="margin:0">完成进度</label><b class="mono" id="percent">{{ pct }}%</b></div>
      <div class="track" role="progressbar" aria-label="故事完成进度" aria-valuemin="0" aria-valuemax="100"><i id="progressfill" :style="{ width: pct + '%' }"></i></div>
      <div class="small" id="progressdesc" style="margin-top:6px">{{ done }} / {{ data.length }} 个故事已完成</div>
    </div>
  </section>
</template>
