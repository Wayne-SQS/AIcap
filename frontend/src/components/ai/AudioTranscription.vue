<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { transcribeAudio } from '@/api/transcription'
import { useMeetingStore } from '@/stores/meeting'
import TranscriptVersions from './TranscriptVersions.vue'
import SpeakerDiarization from './SpeakerDiarization.vue'
const props = defineProps({ meetingId: String, audio: Object })
const meeting = useMeetingStore()
const language = ref(null), busy = ref(false), result = ref(null), diarization = ref(null), error = ref('')
let version = 0
onBeforeUnmount(() => { ++version })
watch(() => [props.meetingId, props.audio.id, language.value], () => { ++version; result.value = null; diarization.value = null; error.value = ''; busy.value = false })
const messages = { stt_model_not_installed: '尚未安装本地转写模型，请先完成语音服务配置。', stt_dependency_missing: '尚未安装语音运行依赖。', stt_busy: '正在处理另一段音频，请稍后重试。', stt_timeout: '本次转写超时，请缩短录音后重试。', audio_duration_exceeded: '当前只支持10分钟以内的音频，请拆分后上传。', invalid_audio: '无法解码此音频，请检查文件是否能正常播放。' }
async function run() {
  if (busy.value || !meeting.maySubmit) return
  const attempt = ++version; busy.value = true; result.value = null; error.value = ''
  try { const data = await transcribeAudio(props.meetingId, props.audio, language.value); if (attempt === version) result.value = data }
  catch (err) { if (attempt === version) error.value = messages[err.message] || `转写失败：${err.message}` }
  finally { if (attempt === version) busy.value = false }
}
const seconds = ms => (ms/1000).toFixed(2)
</script>
<template>
  <section class="transcription" :aria-label="'音频转写：' + audio.filename">
    <template v-if="meeting.maySubmit">
      <label>转写语言 <select v-model="language" :disabled="busy"><option :value="null">自动检测</option><option value="zh">中文</option><option value="en">英语</option></select></label>
      <button type="button" :disabled="busy" @click="run">{{ busy ? '正在本地转写…' : '转写此音频' }}</button>
      <p>最多10分钟，处理可能需要数分钟。草稿需人工核对，不自动覆盖会议原文；未保存的结果刷新后不保留。</p>
    </template>
    <p v-if="error" role="alert">{{ error }}</p>
    <div v-if="result">
      <p v-if="result.status === 'no_speech'" role="status">未识别到可转写的语音，请核对录音。</p>
      <template v-else>
        <p role="status">转写草稿 · 实测时长 {{ seconds(result.duration_ms) }} 秒 · 说话人未知（该文本尚未绑定下方匿名时间段）</p>
        <ol><li v-for="s in result.segments" :key="s.segment_id">{{ seconds(s.start_ms) }}–{{ seconds(s.end_ms) }} 秒 · 未知说话人：{{ s.text }}</li></ol>
        <label class="field">转写全文（只读，可选中复制）<textarea :value="result.text" readonly rows="5" /></label>
      </template>
    </div>
    <TranscriptVersions :meeting-id="meetingId" :audio="audio" :draft="result" :diarization="diarization" />
    <SpeakerDiarization :meeting-id="meetingId" :audio="audio" @preview="diarization = $event" />
  </section>
</template>
<style scoped>.transcription { flex-basis: 100%; padding: 8px; border-top: 1px solid var(--line, #ddd); } button { margin-left: 8px; }</style>
