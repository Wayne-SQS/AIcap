<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { statusAnalysesApi } from '@/api/statusAnalyses'
import { membersApi } from '@/api/members'
import { useMeetingStore } from '@/stores/meeting'
import { statuses } from '@/constants'
import { useReviewStoryChoices } from './useReviewStoryChoices'

const props = defineProps({ meetingId: String, analysisId: String, proposal: Object })
const emit = defineEmits(['saved', 'refresh'])
const meeting = useMeetingStore()
const decision = ref('approve')
const reason = ref('')
const target = ref(props.proposal.changes.status)
const storyId = ref(props.proposal.story_id)
const { stories, loadingStories, storyError, loadStories, stopStoryChoices } = useReviewStoryChoices()
const members = ref([])
const memberError = ref('')
const loadingMembers = ref(true)
const storyFields = ref(null)
const selectedStory = computed(() => stories.value.find(story => story.id === storyId.value))
watch(selectedStory, story => {
  storyFields.value = story ? {
    title: story.title, description: story.description, acceptance: story.acceptance,
    priority: story.priority, sprint: story.sprint, activity: story.activity, owner_id: story.owner_id
  } : null
})
async function loadMembers() {
  loadingMembers.value = true
  memberError.value = ''
  try {
    const rows = await membersApi.profiles()
    if (!Array.isArray(rows)) throw new Error()
    if (mounted) members.value = rows
  } catch { if (mounted) memberError.value = '负责人目录加载失败，请重试。' }
  finally { if (mounted) loadingMembers.value = false }
}
onMounted(loadMembers)
const busy = ref(false)
const error = ref('')
let mounted = true
onBeforeUnmount(() => { mounted = false; stopStoryChoices() })
async function submit() {
  if (busy.value || !meeting.mayReview) return
  error.value = ''
  const payload = { proposal_id: props.proposal.proposal_id, decision: decision.value, reason: reason.value }
  if (decision.value === 'modify_and_approve') {
    if (!reason.value.trim() || !selectedStory.value || !storyFields.value || loadingMembers.value || memberError.value
      || !Number.isInteger(target.value) || target.value < 0 || target.value > 2
      || !storyFields.value.title?.trim()) {
      error.value = '请加载目录，填写目标故事及批准内容，并说明修改原因。'
      return
    }
    const patch = Object.fromEntries(Object.entries(storyFields.value)
      .filter(([key, value]) => value !== selectedStory.value[key]))
    if (storyId.value === props.proposal.story_id && target.value === props.proposal.changes.status && !Object.keys(patch).length) {
      error.value = '请至少修改目标故事、状态或其他故事内容。'
      return
    }
    payload.story_id = storyId.value
    payload.changes = { status: target.value }
    if (Object.keys(patch).length) payload.story_patch = patch
  }
  busy.value = true
  try {
    await statusAnalysesApi.review(props.meetingId, props.analysisId, payload)
    if (mounted) emit('saved')
  } catch (err) {
    if (mounted) error.value = `未能确认审核结果：${err.message}。可刷新处理状态核对；重试同一决定不会重复记录。`
  } finally {
    if (mounted) busy.value = false
  }
}
</script>

<template>
  <form aria-label="审核状态提案" @submit.prevent="submit">
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
        <p class="small">原识别故事：{{ proposal.story_id }}。原提案与证据保留；执行前核对批准时的故事内容。</p>
      </template>
      <label v-if="decision === 'modify_and_approve'" class="field">批准后的目标状态
        <select v-model="target" required>
          <option disabled value="">请选择目标状态</option>
          <option v-for="item in statuses.map((label, status) => ({ label, status }))" :key="item.status" :value="item.status">{{ item.label }}</option>
        </select>
      </label>
      <template v-if="decision === 'modify_and_approve' && storyFields">
        <label class="field">批准后的 Sprint<select v-model.number="storyFields.sprint"><option v-for="n in 4" :key="n" :value="n">Sprint {{ n }}</option></select></label>
        <label class="field">批准后的负责人<select v-model="storyFields.owner_id" :disabled="loadingMembers || !!memberError">
          <option :value="null">未分配</option>
          <option v-if="storyFields.owner_id !== null && !members.some(member => member.user_id === storyFields.owner_id)" :value="storyFields.owner_id">当前负责人 #{{ storyFields.owner_id }}（不在成员目录）</option>
          <option v-for="member in members" :key="member.user_id" :value="member.user_id">{{ member.display_name }} #{{ member.user_id }}</option>
        </select></label>
        <p v-if="memberError" role="alert">{{ memberError }} <button type="button" @click="loadMembers">重试</button></p>
        <label class="field">批准后的故事标题<input v-model="storyFields.title" required maxlength="200"></label>
        <label class="field">批准后的故事描述<textarea v-model="storyFields.description" rows="2"></textarea></label>
        <label class="field">批准后的验收条件<textarea v-model="storyFields.acceptance" rows="2"></textarea></label>
        <label class="field">批准后的优先级<select v-model="storyFields.priority"><option value="Must">Must</option><option value="Should">Should</option><option value="Could">Could</option></select></label>
        <label class="field">批准后的活动<select v-model.number="storyFields.activity"><option v-for="n in 5" :key="n" :value="n">活动 {{ n }}</option></select></label>
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
select, textarea, input { width: 100%; }
</style>
