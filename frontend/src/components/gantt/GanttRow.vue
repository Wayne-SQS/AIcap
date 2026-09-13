<script setup>
/* 甘特图行:父行(parent)/子行(child)/管理行(mgmt)/分组标题(group) 四态
   bar 为可点击按钮(selection 由父组件持有,点击只上报 key);
   meta 为行内任务细节段(ID/负责人/工时/Sprint/关联 Story/看板卡) */
defineProps({
  variant: { type: String, default: 'child' },  // parent | child | mgmt | group
  title: { type: String, required: true },
  sub: { type: String, default: '' },
  meta: { type: Array, default: () => [] },     // [{ text, cls?, title? }]
  /* bar: { left, width, title, cls, label, pct, key, selected, relation } */
  bar: { type: Object, default: null }
})
const emit = defineEmits(['select'])
</script>

<template>
  <div class="grow" :class="[variant, { selected: bar && bar.selected }]">
    <div class="tname">
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
        :class="[bar.cls, bar.relation || '', { selected: bar.selected }]"
        :data-gantt-task="bar.key"
        :style="{ left: bar.left + '%', width: bar.width + '%' }"
        :title="bar.title"
        @click="emit('select', bar.key)"
      ><i v-if="bar.pct != null" class="pfill" :style="{ width: bar.pct + '%' }"></i><span style="position:relative;z-index:1">{{ bar.label }}</span></button>
      <div class="ggrid"><div v-for="w in 6" :key="w" class="gcell"></div></div>
    </div>
  </div>
</template>
