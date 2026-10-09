<script setup>
import { computed, nextTick, ref, watch, onBeforeUnmount } from 'vue'
import { listVersions, saveVersion, confirmVersion } from '@/api/transcriptVersions'
import { useMeetingStore } from '@/stores/meeting'
const props = defineProps({ meetingId: String, audio: Object, draft: Object, diarization: Object })
const emit = defineEmits(['play-range'])
const meeting = useMeetingStore()
const sourceMeeting = computed(() => meeting.meetings.find(row => row.id === props.meetingId) || null)
const analysisMeeting = computed(() => {
  const id = selected.value?.confirmation?.analysis_meeting_id
  return meeting.meetings.find(row => row.id === id) || null
})
const rows = ref([]), selected = ref(null), text = ref(''), title = ref(''), acknowledged = ref(false)
const busy = ref(false), error = ref(''), notice = ref(''), pending = ref(null)
const assignments = ref([]), splitPositions = ref({}), activeIndex = ref(-1), playbackRate = ref(1)
let generation = 0
onBeforeUnmount(() => { ++generation })
function transientPreview() {
  const value = props.diarization
  if (value?.status !== 'draft' || value.audio?.sha256 !== props.audio.sha256) return null
  return value
}
function previewFor(row = selected.value) { return row?.draft?.diarization || transientPreview() || null }
function overlappingSpeakers(segment,row = selected.value) {
  const speakers=[]
  for(const turn of previewFor(row)?.turns || []) {
    const overlap=Math.max(0,Math.min(segment.end_ms,turn.end_ms)-Math.max(segment.start_ms,turn.start_ms))
    if(overlap>0 && !speakers.includes(turn.speaker_id)) speakers.push(turn.speaker_id)
  }
  return speakers
}
function supportsWordAlignment(row) { return row?.draft?.segments?.length && row.draft.segments.every(segment=>segment.words?.length) }
function usesAuditedCorrections(row = selected.value) {
  const version=row?.confirmation?.input?.speaker_alignment?.alignment_version
  return version>=4 || (!row?.confirmation && supportsWordAlignment(row) && !!previewFor(row))
}
function correctedText(item) { return item.corrected_text ?? item.text ?? '' }
function composeCorrectedText() {
  if(!selected.value) return ''
  const lines=selected.value.draft.segments.map(segment=>assignments.value
    .filter(item=>item.segment_id===segment.segment_id).map(correctedText).join('')).filter(Boolean)
  return lines.join('\n')
}
function syncCorrectedText() { if(usesAuditedCorrections()) text.value=composeCorrectedText() }
function updateCorrectedText(index,value) { assignments.value[index].corrected_text=value; syncCorrectedText() }
function segmentFor(item) { return selected.value?.draft?.segments?.find(segment=>segment.segment_id===item.segment_id) }
function wordsFor(item) {
  const segment=segmentFor(item)
  return item.word_ids?.map(id=>segment?.words?.find(word=>word.word_id===id)).filter(Boolean) || []
}
function wordAssignment(segment,words,speakerId,correction=null) {
  const interval={start_ms:words[0].start_ms,end_ms:words.at(-1).end_ms}, value=words.map(word=>word.text).join('')
  return {segment_id:segment.segment_id,word_ids:words.map(word=>word.word_id),text:value,speaker_id:speakerId,
    corrected_text:correction ?? value,overlapping_speakers:overlappingSpeakers(interval),display_text:value.trim(),display_start_ms:interval.start_ms,display_end_ms:interval.end_ms}
}
function renumberAssignments() {
  const counts={}
  assignments.value=assignments.value.map(item=>item.word_ids
    ? {...item,assignment_id:`${item.segment_id}A${counts[item.segment_id]=(counts[item.segment_id] || 0)+1}`}
    : item)
  splitPositions.value={}
  if(activeIndex.value>=assignments.value.length) activeIndex.value=assignments.value.length-1
  syncCorrectedText()
}
function playAssignment(index) {
  const item=assignments.value[index]
  if(!item || busy.value) return
  activeIndex.value=index
  const id=item.assignment_id || item.segment_id
  emit('play-range',{assignment_id:id,start_ms:item.display_start_ms,end_ms:item.display_end_ms,playback_rate:playbackRate.value})
  nextTick(()=>document.getElementById(`alignment-${props.audio.id}-${id}`)?.scrollIntoView({block:'nearest',behavior:'smooth'}))
}
function movePlayback(offset) {
  const base=activeIndex.value<0 && offset>0 ? -1 : Math.max(0,activeIndex.value)
  playAssignment(Math.max(0,Math.min(assignments.value.length-1,base+offset)))
}
function handlePlaybackKey(event) {
  if(event.target!==event.currentTarget || busy.value || !assignments.value.length) return
  if(event.key==='ArrowLeft' && activeIndex.value>0) { event.preventDefault(); movePlayback(-1) }
  else if(event.key==='ArrowRight' && activeIndex.value<assignments.value.length-1) { event.preventDefault(); movePlayback(1) }
  else if((event.key===' ' || event.key==='Enter')) { event.preventDefault(); playAssignment(activeIndex.value<0 ? 0 : activeIndex.value) }
}
function splitOptions(item) {
  const words=wordsFor(item)
  return words.slice(0,-1).map((word,index)=>({value:index+1,label:`${word.word_id} 后 · ${word.text.trim() || '空白词'}`}))
}
function splitAssignment(index) {
  const item=assignments.value[index], segment=segmentFor(item), words=wordsFor(item)
  const at=Number(splitPositions.value[item.assignment_id] || 1)
  if(!segment || at<1 || at>=words.length || correctedText(item)!==item.text) return
  assignments.value.splice(index,1,wordAssignment(segment,words.slice(0,at),item.speaker_id),wordAssignment(segment,words.slice(at),item.speaker_id))
  renumberAssignments()
}
function canMergePrevious(index) {
  const item=assignments.value[index], previous=assignments.value[index-1]
  return !!item?.word_ids?.length && !!previous?.word_ids?.length && item.segment_id===previous.segment_id
}
function mergePrevious(index) {
  if(!canMergePrevious(index)) return
  const item=assignments.value[index], previous=assignments.value[index-1], segment=segmentFor(item)
  const speakerId=previous.speaker_id===item.speaker_id ? item.speaker_id : null
  assignments.value.splice(index-1,2,wordAssignment(segment,[...wordsFor(previous),...wordsFor(item)],speakerId,correctedText(previous)+correctedText(item)))
  renumberAssignments()
}
function buildAssignments(row) {
  const stored=row.confirmation?.input?.speaker_alignment?.assignments
  if(stored) return stored.map(item=>{
    const segment=row.draft.segments.find(value=>value.segment_id===item.segment_id)
    const words=item.word_ids?.map(id=>segment?.words?.find(word=>word.word_id===id)).filter(Boolean) || []
    return {...item,corrected_text:item.corrected_text ?? item.text,display_text:(item.text || segment?.text || '').trim(),display_start_ms:words[0]?.start_ms ?? segment?.start_ms,
      display_end_ms:words.at(-1)?.end_ms ?? segment?.end_ms}
  })
  if(!supportsWordAlignment(row)) return row.draft.segments.map(segment=>({segment_id:segment.segment_id,
    speaker_id:suggestedSpeaker(segment,row),overlapping_speakers:overlappingSpeakers(segment,row),
    display_text:segment.text,display_start_ms:segment.start_ms,display_end_ms:segment.end_ms}))
  const result=[]
  for(const segment of row.draft.segments) {
    const groups=[]
    for(const word of segment.words) {
      const speaker=suggestedSpeaker(word,row), last=groups.at(-1)
      if(last?.speaker_id===speaker) { last.word_ids.push(word.word_id); last.text+=word.text; last.end_ms=word.end_ms }
      else groups.push({speaker_id:speaker,word_ids:[word.word_id],text:word.text,start_ms:word.start_ms,end_ms:word.end_ms})
    }
    groups.forEach((group,index)=>result.push({assignment_id:`${segment.segment_id}A${index+1}`,segment_id:segment.segment_id,
      word_ids:group.word_ids,text:group.text,corrected_text:group.text,speaker_id:group.speaker_id,overlapping_speakers:overlappingSpeakers(group,row),
      display_text:group.text.trim(),display_start_ms:group.start_ms,display_end_ms:group.end_ms}))
  }
  return result
}
function choose(row) {
  selected.value = row; text.value = row.confirmation?.input.text || row.draft.text
  title.value = row.confirmation?.input.title || `${meeting.selectedMeeting?.title || '会议'} · 核对转写`.slice(0, 200)
  acknowledged.value = false; pending.value = null
  assignments.value = buildAssignments(row); splitPositions.value = {}; activeIndex.value = -1
  if(!row.confirmation && usesAuditedCorrections(row)) syncCorrectedText()
}
function suggestedSpeaker(segment,row = selected.value) {
  const preview = previewFor(row)
  if (!preview?.turns?.length) return null
  let best=null,bestOverlap=0
  for(const turn of preview.turns) {
    const overlap=Math.max(0,Math.min(segment.end_ms,turn.end_ms)-Math.max(segment.start_ms,turn.start_ms))
    if(overlap>bestOverlap) { bestOverlap=overlap; best=turn.speaker_id }
  }
  return best
}
watch(() => props.diarization, value => {
  if (!value || pending.value || selected.value?.confirmation || !selected.value) return
  assignments.value=buildAssignments(selected.value); syncCorrectedText()
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
    const row = await saveVersion(props.meetingId, props.draft, props.diarization)
    if (attempt === generation) { put(row); notice.value = '转写版本已保存，可刷新恢复。' }
  } catch (e) { if (attempt === generation) error.value = `保存未确认，可重试同一草稿或刷新历史：${e.message}` }
  finally { if (attempt === generation) busy.value = false }
}
async function confirm() {
  if (busy.value || !meeting.maySubmit || !selected.value || selected.value.confirmation) return
  syncCorrectedText()
  if (!pending.value && (!text.value.trim() || !title.value.trim() || !acknowledged.value)) { error.value = '请填写文本和标题，并确认已核对录音。'; return }
  const preview = previewFor(selected.value)
  const wordAlignment=supportsWordAlignment(selected.value)
  const speaker_alignment = preview
    ? { alignment_version:wordAlignment ? 4 : 2, engine:preview.engine, audio_sha256:selected.value.draft.sha256, duration_ms:preview.duration_ms,
        speaker_count:preview.speaker_count, turns:preview.turns,
        assignments:assignments.value.map(item=>wordAlignment
          ? {assignment_id:item.assignment_id,segment_id:item.segment_id,word_ids:item.word_ids,text:item.text,corrected_text:correctedText(item),
              speaker_id:item.speaker_id || null,overlapping_speakers:item.overlapping_speakers}
          : {segment_id:item.segment_id,speaker_id:item.speaker_id || null,overlapping_speakers:item.overlapping_speakers}) }
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
        <section class="provenance-chain" aria-label="语音会议来源链">
          <h4>来源与产物</h4>
          <ol>
            <li><b>原会议</b><span>{{ sourceMeeting?.title || '当前已选会议' }} · <code>{{ meetingId }}</code></span></li>
            <li><b>录音</b><span>{{ audio.filename || '会议音频' }} · <code>{{ audio.id }}</code> · SHA-256 {{ (audio.sha256 || '').slice(0, 12) }}…</span></li>
            <li><b>转写版本</b><span><code>{{ selected.id }}</code> · {{ selected.created_at }} · {{ selected.confirmation ? '已确认' : '待人工核对' }}</span></li>
            <li><b>人工核对</b><span v-if="selected.confirmation">确认人 #{{ selected.confirmation.confirmed_by }} · {{ selected.confirmation.confirmed_at }}</span><span v-else>尚未确认；确认后会创建独立分析会议。</span></li>
            <li><b>分析会议</b><span v-if="selected.confirmation">{{ analysisMeeting?.title || '分析会议记录' }} · <code>{{ selected.confirmation.analysis_meeting_id }}</code> <button type="button" :disabled="busy" @click="openAnalysis">打开分析会议</button></span><span v-else>确认转写前尚未创建。</span></li>
          </ol>
          <p class="small">分析会议会保留为独立记录，原会议、录音和转写版本不会被覆盖。</p>
        </section>
        <details><summary>查看保存的原始片段</summary><ol><li v-for="s in selected.draft.segments" :key="s.segment_id">{{ s.start_ms/1000 }}–{{ s.end_ms/1000 }} 秒：{{ s.text }}</li></ol></details>
        <label class="field">分析会议标题<input v-model="title" maxlength="200" :disabled="busy || !!pending || !!selected.confirmation || !meeting.maySubmit"></label>
        <label class="field">{{ usesAuditedCorrections(selected) ? '人工核对全文（由下方子段修订生成）' : '人工核对文本' }}<textarea v-model="text" maxlength="16000" rows="6" :readonly="usesAuditedCorrections(selected)" :disabled="busy || !!pending || !!selected.confirmation || !meeting.maySubmit" /></label>
        <fieldset v-if="previewFor(selected) || selected.confirmation?.input?.speaker_alignment">
          <legend>转写片段与匿名说话人对齐</legend>
          <p>按已保存的时间段快照预填；有词时间戳时按真实词边界分组，可在词后拆分或与同一原始片段的上一组合并。合并不同主说话人的组后需重新选择。仅供人工核对，可改为未知。</p>
          <div class="alignment-nav" role="group" aria-label="逐段播放控制" aria-keyshortcuts="ArrowLeft Space Enter ArrowRight" tabindex="0" @keydown="handlePlaybackKey">
            <button type="button" :disabled="busy || !assignments.length || activeIndex <= 0" @click="movePlayback(-1)">播放上一子段</button>
            <span role="status">{{ activeIndex < 0 ? '尚未开始逐段核对' : `正在核对 ${activeIndex+1}/${assignments.length}` }}</span>
            <button type="button" :disabled="busy || !assignments.length || activeIndex >= assignments.length-1" @click="movePlayback(1)">{{ activeIndex < 0 ? '开始逐段播放' : '播放下一子段' }}</button>
            <label>核对播放速度<select v-model.number="playbackRate" aria-label="核对播放速度"><option :value="0.75">0.75×</option><option :value="1">1×</option><option :value="1.25">1.25×</option><option :value="1.5">1.5×</option><option :value="2">2×</option></select></label>
            <small>聚焦本栏后：← 上一段，空格/回车重播，→ 下一段</small>
          </div>
          <div v-for="(item,index) in assignments" :id="`alignment-${audio.id}-${item.assignment_id || item.segment_id}`" :key="item.assignment_id || item.segment_id" class="field alignment-row" :class="{active:index===activeIndex}" :aria-current="index===activeIndex ? 'true' : undefined">
            <span>{{ item.assignment_id || item.segment_id }} · {{ item.display_start_ms/1000 }}–{{ item.display_end_ms/1000 }} 秒 · {{ item.display_text }}</span>
            <label v-if="item.word_ids">人工修订文本<textarea :value="correctedText(item)" rows="2" maxlength="4000" :aria-label="`${item.assignment_id} 人工修订文本`" :disabled="busy || !!pending || !!selected.confirmation || !meeting.maySubmit" @input="updateCorrectedText(index,$event.target.value)" /></label>
            <small v-if="item.word_ids && correctedText(item)!==item.text">已修订；原始识别文字继续保留在上方。</small>
            <span v-if="assignments[index].overlapping_speakers.length > 1" role="status">跨说话人时间段：{{ assignments[index].overlapping_speakers.join('、') }}；请选择主说话人</span>
            <button type="button" :aria-label="`播放 ${item.assignment_id || item.segment_id}`" :disabled="busy" @click="playAssignment(index)">播放此子段</button>
            <select v-model="assignments[index].speaker_id" :aria-label="`${item.assignment_id || item.segment_id} · ${item.display_text}`" :disabled="busy || !!pending || !!selected.confirmation || !meeting.maySubmit">
              <option value="">未知</option>
              <option v-for="n in (previewFor(selected)?.speaker_count || selected.confirmation?.input?.speaker_alignment?.speaker_count || 0)" :key="n" :value="`SPK${n}`">SPK{{ n }}</option>
            </select>
            <template v-if="item.word_ids && !selected.confirmation && meeting.maySubmit">
              <select v-if="item.word_ids.length > 1" v-model="splitPositions[item.assignment_id]" :aria-label="`${item.assignment_id} 拆分位置`" :disabled="busy || !!pending">
                <option value="">选择词边界</option>
                <option v-for="option in splitOptions(item)" :key="option.value" :value="option.value">{{ option.label }}</option>
              </select>
              <button v-if="item.word_ids.length > 1" type="button" :aria-label="`${item.assignment_id} 拆分此子段`" :title="correctedText(item)!==item.text ? '该子段已有文字修订；恢复原始文字后才能拆分' : ''" :disabled="busy || !!pending || !splitPositions[item.assignment_id] || correctedText(item)!==item.text" @click="splitAssignment(index)">拆分此子段</button>
              <button v-if="canMergePrevious(index)" type="button" :aria-label="`${item.assignment_id} 与上一子段合并`" :disabled="busy || !!pending" @click="mergePrevious(index)">与上一子段合并</button>
            </template>
          </div>
        </fieldset>
        <template v-if="!selected.confirmation && meeting.maySubmit">
          <label><input type="checkbox" v-model="acknowledged" :disabled="busy || !!pending">我已对照录音核对文本，理解说话人未识别</label>
          <button :disabled="busy" @click="confirm">{{ pending ? '重试原确认提交' : '确认并创建分析会议' }}</button>
        </template>
      </template>
    </template>
  </section>
</template>
<style scoped>
.alignment-nav { display:flex; align-items:center; gap:8px; flex-wrap:wrap; margin:8px 0; }
.alignment-row { border-left:3px solid transparent; padding-left:8px; }
.alignment-row.active { border-left-color:var(--accent, #2878d0); background:rgba(40,120,208,.08); }
.provenance-chain{margin:14px 0;padding:12px;border:1px solid var(--line,#ddd);border-radius:8px}.provenance-chain h4{margin:0 0 8px}.provenance-chain ol{display:grid;gap:8px;margin:0;padding-left:22px}.provenance-chain li{padding-left:3px}.provenance-chain li b{display:block}.provenance-chain li span{display:block;overflow-wrap:anywhere}.provenance-chain code{font-size:12px}
</style>
