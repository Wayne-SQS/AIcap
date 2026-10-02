<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { diarizeAudio } from '@/api/diarization'
import { useMeetingStore } from '@/stores/meeting'
const props = defineProps({ meetingId: String, audio: Object })
const emit = defineEmits(['preview'])
const meeting = useMeetingStore()
const hint = ref(null), busy = ref(false), result = ref(null), error = ref('')
let version = 0
onBeforeUnmount(() => { ++version })
watch(() => [props.meetingId, props.audio.id, hint.value], () => { ++version; busy.value=false; result.value=null; error.value=''; emit('preview',null) })
const messages = { diarization_model_not_installed:'尚未安装本地说话人分离模型。', diarization_dependency_missing:'尚未安装说话人分离运行依赖。', stt_busy:'语音服务正在处理另一项任务，请稍后重试。', diarization_timeout:'本次说话人分离超时，请缩短录音后重试。', invalid_audio:'无法解码此音频。' }
async function run() {
  if (busy.value || !meeting.maySubmit) return
  const attempt=++version; busy.value=true; result.value=null; error.value=''
  try { const data=await diarizeAudio(props.meetingId,props.audio,hint.value); if(attempt===version) { result.value=data; emit('preview',data) } }
  catch(e) { if(attempt===version) error.value=messages[e.message] || `说话人分离失败：${e.message}` }
  finally { if(attempt===version) busy.value=false }
}
const seconds = ms => (ms/1000).toFixed(2)
</script>
<template>
  <section class="diarization" :aria-label="'说话人分离：' + audio.filename">
    <template v-if="meeting.maySubmit">
      <label>预计人数 <select v-model="hint" :disabled="busy"><option :value="null">自动估计</option><option v-for="n in 8" :key="n" :value="n">{{ n }} 人</option></select></label>
      <button type="button" :disabled="busy" @click="run">{{ busy ? '正在本地分离…' : '分析说话人时间段' }}</button>
      <p>匿名标签只在本次音频内有效，不代表成员身份，也尚未自动分配给转写文字。</p>
    </template>
    <p v-if="error" role="alert">{{ error }}</p>
    <div v-if="result">
      <p v-if="result.status==='no_speech'" role="status">未检测到可分离的语音。</p>
      <template v-else>
        <p role="status">分离草稿 · 检测 {{ result.speaker_count }} 位匿名说话人 · {{ result.turns.length }} 个时间段</p>
        <ol><li v-for="(turn,index) in result.turns" :key="index">{{ seconds(turn.start_ms) }}–{{ seconds(turn.end_ms) }} 秒 · {{ turn.speaker_id }}</li></ol>
        <p>重叠发言可能产生相交时间段；结果需结合回放人工核对。</p>
      </template>
    </div>
  </section>
</template>
<style scoped>.diarization { margin-top: 8px; padding-top: 8px; border-top: 1px dashed var(--line, #ddd); } button { margin-left: 8px; }</style>
