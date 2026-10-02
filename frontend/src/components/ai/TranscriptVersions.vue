<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { listVersions, saveVersion, confirmVersion } from '@/api/transcriptVersions'
import { useMeetingStore } from '@/stores/meeting'
const props = defineProps({ meetingId: String, audio: Object, draft: Object, diarization: Object })
const meeting = useMeetingStore()
const rows = ref([]), selected = ref(null), text = ref(''), title = ref(''), acknowledged = ref(false)
const busy = ref(false), error = ref(''), notice = ref(''), pending = ref(null)
const assignments = ref([])
let generation = 0
onBeforeUnmount(() => { ++generation })
function choose(row) {
  selected.value = row; text.value = row.confirmation?.input.text || row.draft.text
  title.value = row.confirmation?.input.title || `${meeting.selectedMeeting?.title || '会议'} · 核对转写`.slice(0, 200)
  acknowledged.value = false; pending.value = null
  const stored = row.confirmation?.input?.speaker_alignment?.assignments
  assignments.value = row.draft.segments.map(segment => ({ segment_id:segment.segment_id,
    speaker_id:stored?.find(item => item.segment_id===segment.segment_id)?.speaker_id ?? suggestedSpeaker(segment) }))
}
function suggestedSpeaker(segment) {
  if (!props.diarization?.turns?.length || props.diarization.audio?.sha256 !== props.audio.sha256) return null
  let best=null,bestOverlap=0
  for(const turn of props.diarization.turns) {
    const overlap=Math.max(0,Math.min(segment.end_ms,turn.end_ms)-Math.max(segment.start_ms,turn.start_ms))
    if(overlap>bestOverlap) { bestOverlap=overlap; best=turn.speaker_id }
  }
  return best
}
watch(() => props.diarization, value => {
  if (!value || pending.value || selected.value?.confirmation || !selected.value) return
  assignments.value=selected.value.draft.segments.map(segment=>({segment_id:segment.segment_id,speaker_id:suggestedSpeaker(segment)}))
})
function put(row) { rows.value = [row, ...rows.value.filter(r => r.id !== row.id)]; choose(row) }
async function refresh() {
  const attempt = ++generation; busy.value = true; error.value = ''
  try {
    const data = (await listVersions(props.meetingId)).filter(r => r.audio_id === props.audio.id)
    if (attempt !== generation) return
    rows.value = data
    const current = data.find(r => r.id === selected.value?.id)
    if (current?.confirmation || !selected.value) { if (current || data[0]) choose(current || data[0]) }
  } catch (e) { if (attempt === generation) error.value = e.message }
  finally { if (attempt === generation) busy.value = false }
}
watch(() => [props.meetingId, props.audio.id], () => {
  rows.value = []; selected.value = null; pending.value = null; notice.value = ''; refresh()
}, { immediate: true })
async function save() {
  if (busy.value || !meeting.maySubmit || props.draft?.status !== 'draft') return
  const attempt = ++generation; busy.value = true; error.value = ''; notice.value = ''
  try {
    const row = await saveVersion(props.meetingId, props.draft)
    if (attempt === generation) { put(row); notice.value = '转写版本已保存，可刷新恢复。' }
  } catch (e) { if (attempt === generation) error.value = `保存未确认，可重试同一草稿或刷新历史：${e.message}` }
  finally { if (attempt === generation) busy.value = false }
}
async function confirm() {
  if (busy.value || !meeting.maySubmit || !selected.value || selected.value.confirmation) return
  if (!pending.value && (!text.value.trim() || !title.value.trim() || !acknowledged.value)) { error.value = '请填写文本和标题，并确认已核对录音。'; return }
  const speaker_alignment = props.diarization?.status === 'draft' && props.diarization.audio?.sha256 === selected.value.draft.sha256
    ? { engine:props.diarization.engine, audio_sha256:props.diarization.audio.sha256, duration_ms:props.diarization.duration_ms,
        speaker_count:props.diarization.speaker_count, turns:props.diarization.turns,
        assignments:assignments.value.map(item=>({segment_id:item.segment_id,speaker_id:item.speaker_id || null})) }
    : null
  pending.value ||= { title: title.value, text: text.value, acknowledged: true, speaker_alignment }
  const attempt = ++generation; busy.value = true; error.value = ''; notice.value = ''
  try {
    const row = await confirmVersion(props.meetingId, selected.value.id, pending.value)
    if (attempt === generation) { put(row); notice.value = '人工核对已保存，分析会议已创建。' }
  } catch (e) { if (attempt === generation) error.value = `确认结果待核对，请重试原提交或刷新历史：${e.message}` }
  finally { if (attempt === generation) busy.value = false }
}
async function openAnalysis() {
  const id = selected.value?.confirmation?.analysis_meeting_id, attempt = generation
  if (!id || busy.value) return
  busy.value = true; error.value = ''
  try {
    await meeting.loadAll()
    if (attempt !== generation) return
    if (!meeting.meetings.some(m => m.id === id)) throw new Error('未找到分析会议，请刷新重试')
    meeting.select(id)
  } catch (e) { if (attempt === generation) error.value = e.message }
  finally { if (attempt === generation) busy.value = false }
}
</script>
<template>
  <section aria-label="转写版本与人工核对">
    <button v-if="meeting.maySubmit && draft?.status === 'draft'" :disabled="busy" @click="save">保存转写版本</button>
    <button :disabled="busy" @click="refresh">刷新转写历史</button>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="!rows.length">暂无已保存转写版本。</p>
    <template v-else>
      <label>已保存转写版本<select :value="selected?.id" :disabled="busy || !!pending" @change="choose(rows.find(r => r.id === $event.target.value))"><option v-for="row in rows" :key="row.id" :value="row.id">{{ row.created_at }} · {{ row.confirmation ? '已人工确认' : '待核对' }} · {{ row.id.slice(0, 8) }}</option></select></label>
      <template v-if="selected">
        <p>保留提交的原始草稿；需对照上方音频人工核对，说话人仍未知。确认后新建分析会议，原会议保持不变。</p>
        <details><summary>查看保存的原始片段</summary><ol><li v-for="s in selected.draft.segments" :key="s.segment_id">{{ s.start_ms/1000 }}–{{ s.end_ms/1000 }} 秒：{{ s.text }}</li></ol></details>
        <label class="field">分析会议标题<input v-model="title" maxlength="200" :disabled="busy || !!pending || !!selected.confirmation || !meeting.maySubmit"></label>
        <label class="field">人工核对文本<textarea v-model="text" maxlength="16000" rows="6" :disabled="busy || !!pending || !!selected.confirmation || !meeting.maySubmit" /></label>
        <fieldset v-if="props.diarization?.status === 'draft' || selected.confirmation?.input?.speaker_alignment">
          <legend>转写片段与匿名说话人对齐</legend>
          <p>按时间重叠预填，仅供人工核对；可改为未知。确认后保存所用时间段快照。</p>
          <label v-for="(segment,index) in selected.draft.segments" :key="segment.segment_id" class="field">
            {{ segment.segment_id }} · {{ segment.text }}
            <select v-model="assignments[index].speaker_id" :disabled="busy || !!pending || !!selected.confirmation || !meeting.maySubmit">
              <option :value="null">未知</option>
              <option v-for="n in (props.diarization?.speaker_count || selected.confirmation?.input?.speaker_alignment?.speaker_count || 0)" :key="n" :value="`SPK${n}`">SPK{{ n }}</option>
            </select>
          </label>
        </fieldset>
        <template v-if="!selected.confirmation && meeting.maySubmit">
          <label><input type="checkbox" v-model="acknowledged" :disabled="busy || !!pending">我已对照录音核对文本，理解说话人未识别</label>
          <button :disabled="busy" @click="confirm">{{ pending ? '重试原确认提交' : '确认并创建分析会议' }}</button>
        </template>
        <template v-if="selected.confirmation"><p>确认人 #{{ selected.confirmation.confirmed_by }} · {{ selected.confirmation.confirmed_at }}</p><button :disabled="busy" @click="openAnalysis">打开分析会议</button></template>
      </template>
    </template>
  </section>
</template>
