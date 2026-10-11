<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { refinementAnalysesApi } from '@/api/refinementAnalyses'
import { useMeetingStore } from '@/stores/meeting'
import { activities } from '@/constants'

const props = defineProps({ meetingId: String, analysisId: String, proposal: Object })
const emit = defineEmits(['saved', 'refresh'])
const meeting = useMeetingStore()
const changes = ref({ ...props.proposal.changes })
const labels = { title: '标题', description: '描述', acceptance: '验收标准', priority: '优先级', sprint: 'Sprint', activity: '活动' }
const missing = value => Object.keys(labels).filter(key => value[key] == null || (typeof value[key] === 'string' && !value[key].trim()))
const originalMissing = computed(() => missing(props.proposal.changes))
const decision = ref(originalMissing.value.length ? 'modify_and_approve' : 'approve')
const reason = ref(''), busy = ref(false), error = ref('')
let mounted = true
onBeforeUnmount(() => { mounted = false })
async function submit() {
  if (busy.value || !meeting.mayReview) return
  error.value = ''
  const payload = { proposal_id: props.proposal.proposal_id, decision: decision.value, reason: reason.value }
  if (decision.value !== 'reject') {
    const value = decision.value === 'approve' ? props.proposal.changes : changes.value
    if (missing(value).length || !['Must', 'Should', 'Could'].includes(value.priority)
        || !Number.isInteger(value.sprint) || value.sprint < 1 || value.sprint > 4
        || !Number.isInteger(value.activity) || value.activity < 1 || value.activity > activities.length) {
      error.value = '请通过修改后接受补齐标题、描述、验收标准、优先级、Sprint和活动。'
      return
    }
    if (decision.value === 'modify_and_approve') {
      if (!reason.value.trim() || Object.keys(labels).every(key => value[key] === props.proposal.changes[key])) {
        error.value = '请实际修改内容并填写修改原因。'
        return
      }
      payload.changes = { ...value }
    }
  }
  busy.value = true
  try { await refinementAnalysesApi.review(props.meetingId, props.analysisId, payload); if (mounted) emit('saved') }
  catch (err) { if (mounted) error.value = `未能确认审核结果：${err.message}。请刷新处理状态核对，或重试同一决定。` }
  finally { if (mounted) busy.value = false }
}
</script>
<template>
  <form aria-label="审核Refinement提案" @submit.prevent="submit">
    <fieldset :disabled="busy || !meeting.mayReview">
      <legend>人工审核</legend>
      <p v-if="originalMissing.length">原提案待补齐：{{ originalMissing.map(key => labels[key]).join('、') }}。</p>
      <label class="field">审核决定<select v-model="decision"><option value="approve">接受</option><option value="modify_and_approve">修改后接受</option><option value="reject">拒绝</option></select></label>
      <template v-if="decision === 'modify_and_approve'">
        <p class="small">人工补充与原提案分开保存。请选择排期和活动，不会自动填入默认值。</p>
        <label class="field">批准后的标题<input v-model="changes.title" required maxlength="200"></label>
        <label class="field">批准后的描述<textarea v-model="changes.description" required maxlength="4000" rows="3"></textarea></label>
        <label class="field">批准后的验收标准<textarea v-model="changes.acceptance" required maxlength="4000" rows="3"></textarea></label>
        <label class="field">批准后的优先级<select v-model="changes.priority" required><option :value="null" disabled>待确认</option><option v-for="p in ['Must', 'Should', 'Could']" :key="p" :value="p">{{ p }}</option></select></label>
        <label class="field">批准后的Sprint<select v-model="changes.sprint" required><option :value="null" disabled>待确认</option><option v-for="s in 4" :key="s" :value="s">Sprint {{ s }}</option></select></label>
        <label class="field">批准后的活动<select v-model="changes.activity" required><option :value="null" disabled>待确认</option><option v-for="(activity, index) in activities" :key="index" :value="index + 1">{{ activity }}</option></select></label>
      </template>
      <label class="field">{{ decision === 'modify_and_approve' ? '修改原因（必填）' : '审核意见（可选）' }}<textarea v-model="reason" :required="decision === 'modify_and_approve'" maxlength="1000"></textarea></label>
      <p class="small">决定提交后不可覆盖。批准后单独执行，新故事初始为待办、未分配负责人。</p>
      <button type="submit" class="primary">{{ busy ? '正在提交审核…' : '提交审核决定' }}</button>
    </fieldset>
    <div v-if="error" role="alert">{{ error }} <button type="button" :disabled="busy" @click="emit('refresh')">刷新处理状态</button></div>
  </form>
</template>
<style scoped>
fieldset { min-width: 0; margin-top: 12px; padding: 12px; border: 1px solid var(--line, #ddd); }
input, select, textarea { width: 100%; }
</style>
