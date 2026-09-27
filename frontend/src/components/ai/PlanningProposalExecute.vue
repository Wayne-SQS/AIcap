<script setup>
import { onBeforeUnmount, ref } from 'vue'
import { planningAnalysesApi } from '@/api/planningAnalyses'
import { useMeetingStore } from '@/stores/meeting'

const props = defineProps({ meetingId: String, analysisId: String, proposalId: String })
const emit = defineEmits(['executed', 'refresh'])
const meeting = useMeetingStore()
const busy = ref(false)
const error = ref('')
let mounted = true
onBeforeUnmount(() => { mounted = false })

async function execute() {
  if (busy.value || !meeting.mayReview) return
  busy.value = true
  error.value = ''
  try {
    const result = await planningAnalysesApi.execute(props.meetingId, props.analysisId, props.proposalId)
    if (mounted) emit('executed', result)
  } catch (err) {
    if (mounted) error.value = `未能确认执行结果：${err.message}。请刷新处理状态核对；重试同一提案不会重复更新故事。`
  } finally {
    if (mounted) busy.value = false
  }
}
</script>

<template>
  <div class="execution-action">
    <p class="small">执行将按上方批准后的变更更新故事，并记录变更日志。</p>
    <button type="button" class="primary" :disabled="busy || !meeting.mayReview" @click="execute">{{ busy ? '正在执行…' : '执行已批准变更' }}</button>
    <div v-if="error" role="alert">{{ error }} <button type="button" :disabled="busy" @click="emit('refresh')">刷新执行状态</button></div>
  </div>
</template>

