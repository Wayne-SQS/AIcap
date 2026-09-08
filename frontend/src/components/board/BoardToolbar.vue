<script setup>
/* 看板工具栏:tabs(#boardtab/#maptab) + 筛选(#search/#sprint/#owner),结构对齐旧版 L508-529 */
defineProps({
  boardView: { type: String, required: true },
  search: { type: String, default: '' },
  sprint: { type: String, default: '1' },
  owner: { type: String, default: 'all' }
})
const emit = defineEmits(['update:boardView', 'update:search', 'update:sprint', 'update:owner'])

function setBoardView(v) {
  // 对齐旧版 setBoardView(L949-957):切地图时强制 sprint=all
  emit('update:boardView', v)
  if (v === 'map') emit('update:sprint', 'all')
}
</script>

<template>
  <div class="toolbar">
    <div class="tabs" role="tablist" aria-label="故事视图">
      <button role="tab" :aria-selected="boardView === 'board'" id="boardtab" aria-controls="boardpanel" @click="setBoardView('board')">▦ 状态看板</button>
      <button role="tab" :aria-selected="boardView === 'map'" id="maptab" aria-controls="mappanel" @click="setBoardView('map')">▤ 故事地图</button>
    </div>
    <div class="filters">
      <input id="search" type="search" aria-label="搜索故事" placeholder="搜索标题 / ID…" :value="search" @input="emit('update:search', $event.target.value)">
      <select id="sprint" aria-label="筛选迭代" :value="sprint" @change="emit('update:sprint', $event.target.value)">
        <option value="1">Sprint 1 · 行走骨架</option>
        <option value="2">Sprint 2 · 深化协作</option>
        <option value="3">Sprint 3 · 交付收口</option>
        <option value="all">全部 Sprint</option>
      </select>
      <select id="owner" aria-label="筛选负责人" :value="owner" @change="emit('update:owner', $event.target.value)">
        <option value="all">所有成员</option>
        <option value="0">成员 1</option>
        <option value="1">成员 2</option>
        <option value="2">成员 3</option>
        <option value="3">成员 4</option>
      </select>
    </div>
  </div>
</template>
