<script setup>
import { onBeforeUnmount, ref } from 'vue'
import { reviewAnalysesApi } from '@/api/reviewAnalyses'
import { useMeetingStore } from '@/stores/meeting'
import { useReviewStoryChoices } from './useReviewStoryChoices'

const props = defineProps({ meetingId: String, analysisId: String, proposal: Object })
const emit = defineEmits(['saved', 'refresh'])
const meeting = useMeetingStore()
const decision = ref('approve')
const reason = ref('')
const amendedReason = ref(props.proposal.reason)
const storyId = ref(props.proposal.story_id)
const { stories, loadingStories, storyError, loadStories, stopStoryChoices } = useReviewStoryChoices()
const busy = ref(false)
const error = ref('')
let mounted = true
onBeforeUnmount(() => { mounted = false; stopStoryChoices() })

async function submit() {
  if (busy.value || !meeting.mayReview) return
  error.value = ''
  const payload = { proposal_id: props.proposal.proposal_id, decision: decision.value, reason: reason.value }
  if (decision.value === 'modify_and_approve') {
    if (!reason.value.trim() || !stories.value.some(story => story.id === storyId.value)
      || !amendedReason.value.trim()
      || (storyId.value === props.proposal.story_id && amendedReason.value === props.proposal.reason)) {
      error.value = '请选择有效的目标故事，修订故事或提案理由，并填写修改原因。'
      return
    }
    payload.story_id = storyId.value
    payload.changes = { reason: amendedReason.value }
  }
  busy.value = true
  try {
    await reviewAnalysesApi.review(props.meetingId, props.analysisId, payload)
    if (mounted) emit('saved')
  } catch (err) {
    if (mounted) error.value = `未能确认审核结果：${err.message}。可刷新处理状态核对；重试同一决定不会重复记录。`
  } finally {
    if (mounted) busy.value = false
  }
}
</script>

<template>
  <form aria-label="审核Review提案" @submit.prevent="submit">
    <fieldset :disabled="busy || !meeting.mayReview">
      <legend>人工审核</legend>
      <label class="field">审核决定
        <select v-model="decision">
          <option value="approve">接受</option>
          <option value="modify_and_approve">修改后接受</option>
          <option value="reject">拒绝</option>
        </select>
      </label>
      <template v-if="decision === 'modify_and_approve'">
        <label class="field">批准后的目标故事
          <select v-model="storyId" required :disabled="loadingStories || !!storyError">
            <option v-for="story in stories" :key="story.id" :value="story.id">{{ story.id }} · {{ story.title }}</option>
          </select>
        </label>
        <p v-if="storyError" role="alert">{{ storyError }} <button type="button" @click="loadStories">重试</button></p>
        <p class="small">原识别故事：{{ proposal.story_id }}</p>
        <label class="field">批准后的提案理由
          <textarea v-model="amendedReason" required maxlength="1000" rows="3"></textarea>
        </label>
      </template>
      <label class="field">{{ decision === 'modify_and_approve' ? '修改原因（必填）' : '审核意见（可选）' }}
        <textarea v-model="reason" :required="decision === 'modify_and_approve'" maxlength="1000" rows="2"></textarea>
      </label>
      <p class="small">审核决定提交后不可覆盖。接受后仍需执行，才会更新故事状态。</p>
      <button class="primary" type="submit">{{ busy ? '正在提交审核…' : '提交审核决定' }}</button>
    </fieldset>
    <div v-if="error" role="alert">{{ error }} <button type="button" :disabled="busy" @click="emit('refresh')">刷新处理状态</button></div>
  </form>
</template>

<style scoped>
fieldset { margin-top: 12px; padding: 12px; border: 1px solid var(--line, #ddd); border-radius: 8px; min-width: 0; }
select, textarea { width: 100%; }
</style>
