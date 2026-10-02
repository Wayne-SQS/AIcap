<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { assignmentSuggestionsApi } from '@/api/assignment'
import { useMeetingStore } from '@/stores/meeting'
import { useProjectStore } from '@/stores/project'
const props = defineProps({ meetingId: String, saved: Object })
const meeting = useMeetingStore(), project = useProjectStore()
const records = ref([]), selected = ref(null), error = ref(''), loading = ref(false), busy = ref(false), boardError = ref('')
const decision = ref('approve'), memberId = ref(null), reason = ref(''), acknowledged = ref(false)
const dimensions = { tech_stack: '技术栈', capabilities: '工作能力', process_domains: '流程领域' }
const ids = values => values === null ? '未知' : values.length ? values.join('、') : '无记录'
const workload = id => selected.value.result.context.members.find(m => m.profile.user_id === id)
let version = 0, alive = true
onBeforeUnmount(() => { alive = false; ++version })
function choose(record) {
  ++version; selected.value = record; busy.value = false; error.value = ''; boardError.value = ''
  decision.value = 'approve'; memberId.value = null; reason.value = ''; acknowledged.value = false
}
async function refresh() {
  if (!props.meetingId || busy.value) return
  const attempt = ++version; loading.value = true; error.value = ''
  try {
    const rows = await assignmentSuggestionsApi.list(props.meetingId)
    if (!alive || attempt !== version) return
    const id = selected.value?.id
    records.value = rows; choose(rows.find(r => r.id === id) || rows[0] || null)
  } catch (err) { if (alive && attempt === version) error.value = `读取建议失败：${err.message}` }
  finally { if (alive) loading.value = false }
}
watch(() => props.meetingId, refresh, { immediate: true })
watch(() => props.saved, record => {
  if (!record || record.meeting_id !== props.meetingId) return
  records.value = [record, ...records.value.filter(r => r.id !== record.id)]; choose(record)
})
async function review() {
  if (busy.value || loading.value || !selected.value || selected.value.review || !meeting.mayReview) return
  if (!reason.value.trim() || (decision.value === 'approve' && (!memberId.value || !acknowledged.value))) {
    error.value = '请填写审核理由；批准时须选择成员并知悉容量尚未验证。'; return
  }
  const record = selected.value, attempt = ++version
  const payload = { decision: decision.value, member_id: decision.value === 'approve' ? memberId.value : null, reason: reason.value.trim(), capacity_acknowledged: acknowledged.value }
  busy.value = true; error.value = ''
  try {
    const result = await assignmentSuggestionsApi.review(props.meetingId, record, payload)
    if (alive && attempt === version) record.review = result
  } catch (err) { if (alive && attempt === version) error.value = `未能确认审核结果：${err.message}。可重试相同决定，或刷新记录核对。` }
  finally { if (alive && attempt === version) busy.value = false }
}
async function refreshBoard() {
  try { await project.loadAll(); if (alive) boardError.value = '' }
  catch { if (alive) boardError.value = '执行已确认，但看板刷新失败，可单独重试刷新。' }
}
async function execute() {
  const record = selected.value
  if (busy.value || loading.value || !meeting.mayReview || record?.review?.execution_status !== 'not_started') return
  const attempt = ++version; busy.value = true; error.value = ''
  try {
    const execution = await assignmentSuggestionsApi.execute(props.meetingId, record)
    if (!alive || attempt !== version) return
    record.review = { ...record.review, execution_status: 'succeeded', execution }
    await refreshBoard()
  } catch (err) { if (alive && attempt === version) error.value = `未能确认执行结果：${err.message}。请刷新记录或重试同一建议。` }
  finally { if (alive && attempt === version) busy.value = false }
}
</script>
<template>
  <section aria-label="分配建议历史与审核">
    <h3>已保存的分配建议</h3>
    <button type="button" :disabled="loading || busy || !meetingId" @click="refresh">刷新分配记录</button>
    <p v-if="loading" role="status">正在读取分配记录…</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="!loading && !error && !records.length">暂无已保存建议。</p>
    <label v-if="records.length" class="field">历史分配建议<select :value="selected?.id" :disabled="busy || loading" @change="choose(records.find(r => r.id === $event.target.value))"><option v-for="r in records" :key="r.id" :value="r.id">{{ r.created_at }} · {{ r.input.story_ids[0] }} · {{ r.id }}</option></select></label>
    <article v-if="selected">
      <p>记录 {{ selected.id }} · 提交人 #{{ selected.submitted_by }} · {{ selected.created_at }}</p>
      <p>故事 {{ selected.result.context.selected_stories[0].id }} · {{ selected.result.context.selected_stories[0].title }}；目标Sprint {{ selected.input.target_sprint ?? '未知' }}</p>
      <p><strong>此处为保存时的快照。容量尚未验证，批准不会立即修改负责人，需单独执行。</strong></p>
      <ul><li v-for="c in selected.result.candidates" :key="c.member_id">第 {{ c.rank }} 名 · {{ c.display_name }}（#{{ c.member_id }}），满足 {{ c.matched_requirements }}/{{ c.total_requirements }} 项
        <ul><li v-for="(m, i) in c.matches" :key="i">{{ dimensions[m.requirement.dimension] }} / {{ m.requirement.name }} ≥ {{ m.requirement.minimum_level }}；画像 {{ m.recorded_level ?? '无记录' }}</li></ul>
        <p>六周容量 {{ workload(c.member_id).profile.six_week_capacity_hours }} 小时（不是Sprint剩余容量）；已关联故事：{{ ids(workload(c.member_id).owned_story_ids) }}；未完成任务：{{ ids(workload(c.member_id).active_task_ids) }}；目标Sprint任务：{{ ids(workload(c.member_id).target_sprint_task_ids) }}</p>
      </li></ul>
      <p v-if="!selected.result.candidates.length">本次没有可选择的候选成员，可记录拒绝理由。</p>
      <form v-if="meeting.mayReview && !selected.review" @submit.prevent="review">
        <fieldset :disabled="busy || loading">
          <legend>人工分配审核</legend>
          <label class="field">分配审核决定<select v-model="decision"><option value="approve">选择候选并批准</option><option value="reject">拒绝</option></select></label>
          <label v-if="decision === 'approve'" class="field">分配给成员<select v-model="memberId" required><option :value="null" disabled>请选择成员</option><option v-for="c in selected.result.candidates" :key="c.member_id" :value="c.member_id">{{ c.display_name }}（#{{ c.member_id }}）</option></select></label>
          <label class="field">分配审核理由<textarea v-model="reason" required maxlength="1000" /></label>
          <label v-if="decision === 'approve'"><input v-model="acknowledged" type="checkbox" required>我已知悉容量尚未验证，已人工核对本次分工</label>
          <button type="submit">{{ busy ? '正在提交…' : '提交分配审核' }}</button>
        </fieldset>
      </form>
      <div v-if="selected.review">
        <p>{{ selected.review.status === 'approved' ? '已批准' : '已拒绝' }} · 审核人 #{{ selected.review.reviewed_by }} · {{ selected.review.reviewed_at }}</p>
        <p>审核理由：{{ selected.review.input.reason }}</p>
        <p v-if="selected.review.status === 'approved'">批准负责人：#{{ selected.review.input.member_id }}</p>
        <button v-if="meeting.mayReview && selected.review.execution_status === 'not_started'" type="button" :disabled="busy || loading" @click="execute">执行已批准分配</button>
        <p v-if="selected.review.execution_status === 'succeeded'" role="status">分配已执行：{{ selected.review.execution.story_id }} → #{{ selected.review.execution.new_owner_id }}；故事日志 #{{ selected.review.execution.story_log_id }}</p>
      </div>
      <p v-else-if="!meeting.mayReview">等待管理员或负责人审核。</p>
    </article>
    <p v-if="boardError" role="alert">{{ boardError }} <button @click="refreshBoard">刷新看板和日志</button></p>
  </section>
</template>
