<script setup>
import { computed } from 'vue'
import { useMeetingStore } from '@/stores/meeting'

const props = defineProps({ proposals: { type: Array, default: () => [] } })
const meeting = useMeetingStore()
const needsAction = computed(() => props.proposals.some(item => item.status === 'pending'
  || item.status === 'approved' && item.execution_status === 'not_started'))
</script>

<template>
  <p v-if="!meeting.mayReview && needsAction" class="workflow-permission-note" role="note">
    当前账号可以查看提案；审核和执行操作仅管理员或负责人可进行。已批准的提案仍需单独执行。
  </p>
</template>

<style scoped>
.workflow-permission-note{padding:10px 12px;border-left:3px solid var(--accent,#5577cc);background:var(--surface-2,#f5f6f8);font-size:13px}
</style>
