<script setup>
import { activities } from '@/constants'
defineProps({ changes: { type: Object, required: true } })
</script>
<template>
  <dl class="story-fields">
    <dt>标题</dt><dd>{{ changes.title }}</dd>
    <dt>描述</dt><dd>{{ changes.description ?? '待确认' }}</dd>
    <dt>验收标准</dt><dd>{{ changes.acceptance ?? '待确认' }}</dd>
    <dt>优先级</dt><dd>{{ changes.priority ?? '待确认' }}</dd>
    <dt>Sprint</dt><dd>{{ changes.sprint == null ? '待确认' : `Sprint ${changes.sprint}` }}</dd>
    <dt>活动</dt><dd>{{ changes.activity == null ? '待确认' : activities[changes.activity - 1] || '未知活动' }}</dd>
  </dl>
</template>
<style scoped>
.story-fields { display: grid; grid-template-columns: 80px minmax(0, 1fr); gap: 6px 12px; }
dt { font-weight: bold; } dd { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
