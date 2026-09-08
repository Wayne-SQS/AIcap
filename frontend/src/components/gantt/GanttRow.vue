<script setup>
/* 甘特图行:纯展示组件,父行(parent)/子行(child)/管理行(mgmt)三态
   结构对齐旧版 renderGantt()(L1016-1029)的 .grow 拼接 */
defineProps({
  variant: { type: String, default: 'child' },  // parent | child | mgmt
  title: { type: String, required: true },
  sub: { type: String, default: '' },
  /* bar 为 null 时仅渲染网格(独立任务分组标题行) */
  bar: { type: Object, default: null }
  /* bar: { left, width, title, cls, label, pct } */
})
</script>

<template>
  <div class="grow" :class="variant">
    <div class="tname"><b>{{ title }}</b><small>{{ sub }}</small></div>
    <div class="gtrack">
      <div
        v-if="bar"
        class="gbar"
        :class="bar.cls"
        :style="{ left: bar.left + '%', width: bar.width + '%' }"
        :title="bar.title"
      ><i v-if="bar.pct != null" class="pfill" :style="{ width: bar.pct + '%' }"></i><span style="position:relative;z-index:1">{{ bar.label }}</span></div>
      <div class="ggrid"><div v-for="w in 6" :key="w" class="gcell"></div></div>
    </div>
  </div>
</template>
