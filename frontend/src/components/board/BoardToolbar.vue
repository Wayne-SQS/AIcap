<script setup>
import { useProjectStore } from '@/stores/project'
import { SPRINTS } from '@/data/seed'

/* 看板工具栏:tabs(#boardtab/#maptab) + 筛选(#search/#sprint/#owner)
   sprint 选项新增「Sprint 4+ · 后续路线」(US09/US15/US21 落在后续路线,不再被筛掉)
   owner 选项来自后端真实成员(loadAll 后为真实姓名) */
defineProps({
  boardView: { type: String, required: true },
  search: { type: String, default: '' },
  sprint: { type: String, default: 'all' },
  owner: { type: String, default: 'all' }
})
const emit = defineEmits(['update:boardView', 'update:search', 'update:sprint', 'update:owner'])
const project = useProjectStore()

function setBoardView(v) {
  // 对齐旧版 setBoardView:切到故事地图时强制 sprint=all(地图本身按切片分列)
  emit('update:boardView', v)
  if (v === 'map') emit('update:sprint', 'all')
}
</script>

<template>
  <div class="toolbar">
    <div class="tabs" role="tablist" aria-label="故事视图">
      <button role="tab" :aria-selected="boardView === 'map'" id="maptab" aria-controls="mappanel" @click="setBoardView('map')">▤ 故事地图</button>
      <button role="tab" :aria-selected="boardView === 'board'" id="boardtab" aria-controls="boardpanel" @click="setBoardView('board')">▦ 状态看板</button>
    </div>
    <div class="filters">
      <input id="search" type="search" aria-label="搜索故事" placeholder="搜索标题 / ID…" :value="search" @input="emit('update:search', $event.target.value)">
      <select id="sprint" aria-label="筛选迭代" :value="sprint" @change="emit('update:sprint', $event.target.value)">
        <option value="1">Sprint 1 · {{ SPRINTS[0].tag }}</option>
        <option value="2">Sprint 2 · {{ SPRINTS[1].tag }}</option>
        <option value="3">Sprint 3 · {{ SPRINTS[2].tag }}</option>
        <option value="4plus">Sprint 4+ · 后续路线</option>
        <option value="all">全部 Sprint</option>
      </select>
      <select id="owner" aria-label="筛选负责人" :value="owner" @change="emit('update:owner', $event.target.value)">
        <option value="all">所有成员</option>
        <option v-for="m in project.members" :key="m.id" :value="String(m.id)">{{ m.name }}</option>
      </select>
    </div>
  </div>
</template>
