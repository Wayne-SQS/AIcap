<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { storiesApi } from '@/api/stories'
import { recommendAssignment, assignmentSuggestionsApi } from '@/api/assignment'
import { useMeetingStore } from '@/stores/meeting'
import { useSessionStore } from '@/stores/session'
import AssignmentHistory from './AssignmentHistory.vue'

const props = defineProps({ meetingId: String })
const meeting = useMeetingStore()
const session = useSessionStore()
const saved = ref(null), pending = ref(null), saving = ref(false), saveError = ref(''), saveNotice = ref('')
const storageKey = `aicap-assignment-save:${session.currentUser?.id}:${props.meetingId}`
try { pending.value = JSON.parse(sessionStorage.getItem(storageKey) || 'null') } catch { saveError.value = '无法读取未完成的保存请求，请检查浏览器会话存储。' }
const stories = ref([]), storyId = ref(''), sprint = ref(null)
const requirements = ref([{ dimension: 'tech_stack', name: '', minimum_level: 3 }])
const result = ref(null), error = ref(''), storyError = ref(''), loading = ref(false), busy = ref(false)
const dimensions = { tech_stack: '技术栈', capabilities: '工作能力', process_domains: '流程领域' }
const statuses = { provisional: '技能候选，仅供人工核对', requirements_needed: '请补充技能要求后再查询', no_eligible_members: '没有符合角色范围的候选成员', no_recorded_skill_match: '画像中暂无满足要求的记录，不代表成员不具备能力' }
const gapNames = { target_sprint_unknown: '目标Sprint尚未确定', story_estimates_unavailable: '故事估时尚不可用', sprint_capacity_unavailable: 'Sprint可用工时和剩余工时尚不可用', members_empty: '成员目录为空', profile_dimensions_empty: '成员画像存在空维度', story_owner_unresolved: '故事负责人未在成员目录中找到', task_owner_unresolved: '任务负责人未在成员目录中找到', task_story_unresolved: '任务关联的故事未找到', target_sprint_task_schedule_unavailable: '现有任务周排期不覆盖Sprint 4' }
let mounted = true, version = 0
onBeforeUnmount(() => { mounted = false; ++version })
watch([storyId, sprint, requirements], () => { ++version; result.value = null; error.value = ''; busy.value = false }, { deep: true, flush: 'sync' })
async function loadStories() {
  loading.value = true; storyError.value = ''; result.value = null; ++version
  try {
    const rows = await storiesApi.list()
    if (!Array.isArray(rows) || rows.some(s => typeof s.id !== 'string' || typeof s.title !== 'string')) throw new Error()
    if (!mounted) return
    stories.value = rows
    if (!rows.some(s => s.id === storyId.value)) storyId.value = ''
  } catch { if (mounted) { stories.value = []; storyId.value = ''; storyError.value = '故事目录加载失败，请重试。' } }
  finally { if (mounted) loading.value = false }
}
onMounted(loadStories)
function payload() {
  const rows = requirements.value.map(r => ({ ...r, name: r.name.trim() }))
  if (!storyId.value || !rows.length || rows.some(r => !r.name || !Number.isInteger(r.minimum_level) || r.minimum_level < 1 || r.minimum_level > 5)) throw new Error('请填写故事、技能名称和最低等级。')
  if (new Set(rows.map(r => `${r.dimension}:${r.name.toLowerCase()}`)).size !== rows.length) throw new Error('同一维度的技能要求不能重复。')
  return { story_ids: [storyId.value], target_sprint: sprint.value, requirements: rows }
}
async function save() {
  if (saving.value || busy.value || !meeting.maySubmit || !props.meetingId) return
  saveError.value = ''; saveNotice.value = ''
  try {
    if (!pending.value) pending.value = { ...payload(), client_request_id: crypto.randomUUID() }
    sessionStorage.setItem(storageKey, JSON.stringify(pending.value))
  } catch (err) { saveError.value = `保存未发出：${err.message}。需要会话存储保留重试请求。`; return }
  const request = JSON.parse(JSON.stringify(pending.value))
  saving.value = true
  try {
    const record = await assignmentSuggestionsApi.save(props.meetingId, request)
    // Remove only this request; a newer mount may already have created another intent.
    try { if (JSON.parse(sessionStorage.getItem(storageKey) || 'null')?.client_request_id === request.client_request_id) sessionStorage.removeItem(storageKey) } catch { /* original key remains safe to retry */ }
    if (!mounted) return
    pending.value = null; saved.value = record
    saveNotice.value = '分配建议已保存，请在下方核对保存的快照并审核。'
  } catch (err) { if (mounted) saveError.value = `未能确认建议保存结果：${err.message}。重试会沿用原条件和请求编号。` }
  finally { if (mounted) saving.value = false }
}
async function submit() {
  if (busy.value || loading.value || !meeting.maySubmit || !props.meetingId || !storyId.value) return
  result.value = null; error.value = ''
  const rows = requirements.value.map(r => ({ ...r, name: r.name.trim() }))
  if (!rows.length || rows.some(r => !r.name || !Number.isInteger(r.minimum_level) || r.minimum_level < 1 || r.minimum_level > 5)) { error.value = '请填写技能名称和最低等级。'; return }
  if (new Set(rows.map(r => `${r.dimension}:${r.name.toLowerCase()}`)).size !== rows.length) { error.value = '同一维度的技能要求不能重复。'; return }
  const payload = { story_ids: [storyId.value], target_sprint: sprint.value, requirements: rows }
  const attempt = ++version
  busy.value = true
  try { const data = await recommendAssignment(props.meetingId, payload); if (mounted && attempt === version) result.value = data }
  catch (err) { if (mounted && attempt === version) error.value = `候选查询失败：${err.message}` }
  finally { if (mounted && attempt === version) busy.value = false }
}
const workload = id => result.value.context.members.find(m => m.profile.user_id === id)
const ids = list => list === null ? '未知' : list.length ? list.join('、') : '无记录'
</script>

<template>
  <section class="assignment-panel" aria-label="Assignment分配候选">
    <h3>分配候选</h3>
    <p>填写本次技能要求，按画像中达到最低等级的项目数等权排序。同分并列，不代表可用工时充足。</p>
    <p><strong>查询只读；保存建议会重新读取事实并计算候选，人工审核后需单独执行才会修改负责人。</strong></p>
    <p v-if="!meetingId">请先选择已保存会议。</p>
    <p v-if="!meeting.maySubmit">管理员、负责人或成员可查询候选；只读角色不能发起查询。</p>
    <p v-if="loading" role="status">正在读取故事目录…</p>
    <div v-if="storyError" role="alert">{{ storyError }} <button :disabled="loading" @click="loadStories">重试故事目录</button></div>
    <form v-if="meeting.maySubmit" @submit.prevent="submit">
      <fieldset :disabled="busy || saving || loading || !meetingId || !!storyError">
        <legend>本次分配条件</legend>
        <label class="field">待分配故事<select v-model="storyId" required><option value="" disabled>请选择故事</option><option v-for="s in stories" :key="s.id" :value="s.id">{{ s.id }} · {{ s.title }}</option></select></label>
        <p v-if="!loading && !storyError && !stories.length">当前没有故事。</p>
        <label class="field">目标Sprint<select v-model="sprint"><option :value="null">未知</option><option v-for="s in 4" :key="s" :value="s">Sprint {{ s }}</option></select></label>
        <fieldset v-for="(requirement, index) in requirements" :key="index" class="requirement">
          <legend>技能要求 {{ index + 1 }}</legend>
          <label class="field">技能维度<select v-model="requirement.dimension"><option v-for="(label, key) in dimensions" :key="key" :value="key">{{ label }}</option></select></label>
          <label class="field">技能名称<input v-model="requirement.name" required maxlength="50" placeholder="如 Python，与画像名称一致"></label>
          <label class="field">最低等级<select v-model="requirement.minimum_level"><option v-for="level in 5" :key="level" :value="level">{{ level }}</option></select></label>
          <button type="button" :disabled="requirements.length === 1" @click="requirements.splice(index, 1)">移除该要求</button>
        </fieldset>
        <button type="button" :disabled="requirements.length >= 20" @click="requirements.push({ dimension: 'tech_stack', name: '', minimum_level: 3 })">添加技能要求</button>
        <button type="submit" class="primary" :disabled="!storyId">{{ busy ? '正在查询…' : '查询分配候选' }}</button>
        <button type="button" :disabled="!storyId || !!pending" @click="save">保存分配建议</button>
      </fieldset>
    </form>
    <p v-if="error" role="alert">{{ error }}</p>
    <div v-if="result" aria-label="候选结果" role="region">
      <h4>{{ statuses[result.status] }}</h4>
      <p>故事 {{ result.context.selected_stories[0].id }} · {{ result.context.selected_stories[0].title }}；目标Sprint：{{ result.context.target_sprint ?? '未知' }}</p>
      <p><strong>容量尚未验证，不能据此直接分配。</strong> 六周容量不能当作Sprint剩余容量；故事、成员和任务为分次读取。</p>
      <h4>数据缺口</h4>
      <ul><li v-for="(gap, index) in result.context.gaps" :key="index">{{ gapNames[gap.code] || '存在未识别的数据缺口，请核对' }}<span v-if="gap.member_ids?.length"> · 成员 #{{ gap.member_ids.join('、#') }}</span><span v-if="gap.story_ids?.length"> · {{ gap.story_ids.join('、') }}</span><span v-if="gap.task_ids?.length"> · {{ gap.task_ids.join('、') }}</span></li></ul>
      <article v-for="candidate in result.candidates" :key="candidate.member_id" class="candidate">
        <h4>第 {{ candidate.rank }} 名 · {{ candidate.display_name }}（#{{ candidate.member_id }}）</h4>
        <p>满足 {{ candidate.matched_requirements }}/{{ candidate.total_requirements }} 项要求 · 容量未知 · 待人工核对</p>
        <ul><li v-for="(match, index) in candidate.matches" :key="index">{{ dimensions[match.requirement.dimension] }} / {{ match.requirement.name }}：要求 ≥ {{ match.requirement.minimum_level }}；画像 {{ match.recorded_level ?? '无记录' }}；{{ match.meets_requirement ? '满足' : '未有满足要求的记录' }}</li></ul>
        <p>六周容量：{{ workload(candidate.member_id).profile.six_week_capacity_hours }} 小时（不是本Sprint可用工时）</p>
        <p>已关联故事：{{ ids(workload(candidate.member_id).owned_story_ids) }}<br>未完成任务：{{ ids(workload(candidate.member_id).active_task_ids) }}<br>与目标Sprint重叠的任务：{{ ids(workload(candidate.member_id).target_sprint_task_ids) }}</p>
      </article>
      <p v-if="result.excluded_viewer_ids.length">只读角色不进入候选：#{{ result.excluded_viewer_ids.join('、#') }}</p>
    </div>
    <p v-if="pending">有尚未确认的保存请求（{{ pending.client_request_id }}），重试将使用原故事和技能要求。
      <button v-if="meeting.maySubmit" type="button" :disabled="saving || busy" @click="save">重试保存分配建议</button>
    </p>
    <p v-if="saveError" role="alert">{{ saveError }}</p>
    <p v-if="saveNotice" role="status">{{ saveNotice }}</p>
    <AssignmentHistory :meeting-id="meetingId" :saved="saved" />
  </section>
</template>
<style scoped>
.assignment-panel { margin: 20px 0; padding: 16px; border: 1px solid var(--line, #ddd); border-radius: 10px; overflow-wrap: anywhere; }
fieldset { min-width: 0; margin: 10px 0; padding: 12px; border: 1px solid var(--line, #ddd); }
input, select { width: 100%; }
.candidate { border-top: 1px solid var(--line, #ddd); margin-top: 16px; }
button { margin: 4px 8px 4px 0; }
</style>
