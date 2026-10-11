<script setup>
import { onBeforeUnmount, ref } from 'vue'
import { retroAnalysesApi } from '@/api/retroAnalyses'
import { useMeetingStore } from '@/stores/meeting'
const props = defineProps({ meetingId: String, analysisId: String, proposal: Object, members: Array, membersReady: Boolean })
const emit = defineEmits(['saved', 'refresh'])
const meeting = useMeetingStore()
const decision = ref('approve'), reason = ref(''), busy = ref(false), error = ref('')
const changes = ref({ ...props.proposal.changes })
let mounted = true
onBeforeUnmount(() => { mounted = false })
async function submit() {
  if (busy.value || !meeting.mayReview) return
  error.value = ''
  const payload = { proposal_id: props.proposal.proposal_id, decision: decision.value, reason: reason.value }
  if (decision.value === 'modify_and_approve') {
    const value = { ...changes.value, deadline_text: changes.value.deadline_text?.trim() || null }
    if (!props.membersReady || !reason.value.trim() || !value.title.trim()
        || Object.keys(value).every(key => value[key] === props.proposal.changes[key])) {
      error.value = '请修改行动内容并填写修改原因；成员目录必须加载成功。'
      return
    }
    payload.changes = value
  }
  busy.value = true
  try { await retroAnalysesApi.review(props.meetingId, props.analysisId, payload); if (mounted) emit('saved') }
  catch (err) { if (mounted) error.value = `未能确认审核结果：${err.message}。请刷新处理状态核对，或重试同一决定。` }
  finally { if (mounted) busy.value = false }
}
</script>
<template>
  <form aria-label="审核Retro提案" @submit.prevent="submit">
    <fieldset :disabled="busy || !meeting.mayReview">
      <legend>人工审核</legend>
      <label class="field">审核决定<select v-model="decision"><option value="approve">接受</option><option value="modify_and_approve">修改后接受</option><option value="reject">拒绝</option></select></label>
      <template v-if="decision === 'modify_and_approve'">
        <p>人工修改会单独保存；原文证据和原提案保留。</p>
        <label class="field">批准后的事项<input v-model="changes.title" required maxlength="200"></label>
        <label class="field">批准后的描述<textarea v-model="changes.description" maxlength="2000"></textarea></label>
        <label class="field">批准后的负责人<select v-model="changes.owner_id" :disabled="!membersReady"><option :value="null">未确定</option><option v-if="changes.owner_id !== null && !members?.some(m => m.user_id === changes.owner_id)" :value="changes.owner_id">原负责人 #{{ changes.owner_id }}（不在当前目录）</option><option v-for="member in members" :key="member.user_id" :value="member.user_id">{{ member.display_name }} #{{ member.user_id }}</option></select></label>
        <label class="field">批准后的截止时间（留空表示未确定）<input v-model="changes.deadline_text" maxlength="200"></label>
      </template>
      <label class="field">{{ decision === 'modify_and_approve' ? '修改原因（必填）' : '审核意见（可选）' }}<textarea v-model="reason" :required="decision === 'modify_and_approve'" maxlength="1000"></textarea></label>
      <p class="small">审核决定提交后不可覆盖；批准后仍需单独执行。</p>
      <button type="submit" class="primary">{{ busy ? '正在提交审核…' : '提交审核决定' }}</button>
    </fieldset>
    <div v-if="error" role="alert">{{ error }} <button type="button" :disabled="busy" @click="emit('refresh')">刷新处理状态</button></div>
  </form>
</template>
<style scoped>
fieldset { min-width: 0; margin-top: 12px; padding: 12px; border: 1px solid var(--line, #ddd); }
input, select, textarea { width: 100%; }
</style>
