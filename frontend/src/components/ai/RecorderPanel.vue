<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { audioApi } from '@/api/audio'
import { useMeetingStore } from '@/stores/meeting'
import { useToast } from '@/composables/useToast'

/* 爱管理自带录音:麦克风 → MediaRecorder → 客户端 lamejs 编码为真 .mp3 → 提交到会议
   说明:浏览器 MediaRecorder 原生只出 webm/opus 或 mp4/aac,不出 mp3;
   因此录音结束后在浏览器内解码为 PCM 并用 lamejs 编码成 mp3(服务端不需要 ffmpeg)。 */
const meeting = useMeetingStore()
const { notify } = useToast()

const recording = ref(false)
const elapsedMs = ref(0)
const busy = ref(false)
const error = ref('')
const audios = ref([])
const clip = ref(null)          // { blob, url, ms, bytes }
const playUrls = ref({})        // id -> objectURL(鉴权取字节后生成)

let recorder = null
let stream = null
let chunks = []
let tick = null
let startedAt = 0
let lamePromise = null

const meetingId = computed(() => meeting.selected)
const maySubmit = computed(() => meeting.maySubmit)
const mayDelete = computed(() => meeting.mayReview)
const supported = typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia
  && typeof window !== 'undefined' && typeof window.MediaRecorder !== 'undefined'

function fmtMs(ms) {
  const total = Math.floor(ms / 1000)
  const m = String(Math.floor(total / 60)).padStart(2, '0')
  const s = String(total % 60).padStart(2, '0')
  return `${m}:${s}`
}
function fmtSize(bytes) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(0) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

/* ---------- lamejs:依赖三个全局,浏览器打包器不会自动注入 ---------- */
function loadLame() {
  if (!lamePromise) {
    lamePromise = (async () => {
      const [mode, lame, bit] = await Promise.all([
        import('lamejs/src/js/MPEGMode.js'),
        import('lamejs/src/js/Lame.js'),
        import('lamejs/src/js/BitStream.js')
      ])
      globalThis.MPEGMode = mode.default || mode
      globalThis.Lame = lame.default || lame
      globalThis.BitStream = bit.default || bit
      const main = await import('lamejs')
      return main.default || main
    })()
  }
  return lamePromise
}

/* ---------- 录音 → mp3 ---------- */
function pickMime() {
  const candidates = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg;codecs=opus']
  return candidates.find(t => window.MediaRecorder.isTypeSupported?.(t)) || ''
}

async function start() {
  error.value = ''
  if (!meetingId.value) { error.value = '请先选择一个已保存会议'; return }
  if (!supported) { error.value = '当前浏览器不支持录音（需要麦克风权限与 MediaRecorder）'; return }
  try {
    stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } })
  } catch (e) {
    error.value = e.name === 'NotAllowedError'
      ? '麦克风权限被拒绝：请在浏览器地址栏允许本页访问麦克风后重试'
      : '无法访问麦克风：' + e.message
    return
  }
  chunks = []
  const mime = pickMime()
  recorder = mime ? new MediaRecorder(stream, { mimeType: mime }) : new MediaRecorder(stream)
  recorder.ondataavailable = ev => { if (ev.data && ev.data.size) chunks.push(ev.data) }
  recorder.onstop = () => finalize(recorder.mimeType || mime || 'audio/webm')
  startedAt = Date.now()
  elapsedMs.value = 0
  tick = setInterval(() => { elapsedMs.value = Date.now() - startedAt }, 200)
  recorder.start(1000)
  recording.value = true
}

function stop() {
  if (recorder && recorder.state !== 'inactive') recorder.stop()
  recording.value = false
  clearInterval(tick)
  tick = null
}

async function finalize(mime) {
  const ms = Date.now() - startedAt
  if (stream) { stream.getTracks().forEach(t => t.stop()); stream = null }
  if (!chunks.length) { error.value = '没有录到音频数据，请检查麦克风'; return }
  busy.value = true
  try {
    const raw = new Blob(chunks, { type: mime })
    const mp3 = await toMp3(raw)
    clearClip()
    clip.value = { blob: mp3, url: URL.createObjectURL(mp3), ms, bytes: mp3.size }
    notify(`录音完成：${fmtMs(ms)} · 已编码为 MP3 ${fmtSize(mp3.size)}`)
  } catch (e) {
    error.value = '录音转 MP3 失败：' + e.message
  } finally {
    busy.value = false
    chunks = []
    recorder = null
  }
}

/** 解码录音字节为 PCM,再用 lamejs 编成单声道 128kbps MP3 */
async function toMp3(blob) {
  const buf = await blob.arrayBuffer()
  const Ctx = window.AudioContext || window.webkitAudioContext
  const ctx = new Ctx()
  const decoded = await ctx.decodeAudioData(buf.slice(0))
  const rate = decoded.sampleRate
  const channels = decoded.numberOfChannels
  const len = decoded.length
  const mono = new Float32Array(len)
  for (let c = 0; c < channels; c++) {
    const data = decoded.getChannelData(c)
    for (let i = 0; i < len; i++) mono[i] += data[i] / channels
  }
  await ctx.close()
  const pcm = new Int16Array(len)
  for (let i = 0; i < len; i++) {
    const s = Math.max(-1, Math.min(1, mono[i]))
    pcm[i] = s < 0 ? s * 0x8000 : s * 0x7fff
  }
  const lamejs = await loadLame()
  const enc = new lamejs.Mp3Encoder(1, rate, 128)
  const parts = []
  const block = 1152
  for (let i = 0; i < pcm.length; i += block) {
    const out = enc.encodeBuffer(pcm.subarray(i, Math.min(i + block, pcm.length)))
    if (out.length) parts.push(new Uint8Array(out))
  }
  const tail = enc.flush()
  if (tail.length) parts.push(new Uint8Array(tail))
  return new Blob(parts, { type: 'audio/mpeg' })
}

/* ---------- 提交 / 列表 / 回放 ---------- */
async function submitClip() {
  if (!clip.value) return
  busy.value = true
  error.value = ''
  try {
    const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)
    const file = new File([clip.value.blob], `录音-${stamp}.mp3`, { type: 'audio/mpeg' })
    const row = await audioApi.upload(meetingId.value, file, { source: 'recorder', durationMs: clip.value.ms })
    notify(`录音已提交到会议（${fmtSize(row.byte_size)}）`)
    clearClip()
    await refresh()
  } catch (e) {
    error.value = e.message
    notify('提交失败：' + e.message)
  } finally {
    busy.value = false
  }
}

async function submitFile(ev) {
  const file = ev.target.files && ev.target.files[0]
  ev.target.value = ''
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.mp3')) { error.value = '仅支持 .mp3 文件'; return }
  busy.value = true
  error.value = ''
  try {
    const row = await audioApi.upload(meetingId.value, file, { source: 'upload' })
    notify(`已提交本地音频（${fmtSize(row.byte_size)}）`)
    await refresh()
  } catch (e) {
    error.value = e.message
    notify('提交失败：' + e.message)
  } finally {
    busy.value = false
  }
}

function clearClip() {
  if (clip.value) URL.revokeObjectURL(clip.value.url)
  clip.value = null
}

async function refresh() {
  if (!meetingId.value) { audios.value = []; return }
  try { audios.value = await audioApi.list(meetingId.value) } catch (e) { error.value = e.message }
}

async function play(row) {
  if (playUrls.value[row.id]) return
  try {
    const blob = await audioApi.fetchBlob(row.id)
    playUrls.value = { ...playUrls.value, [row.id]: URL.createObjectURL(blob) }
  } catch (e) { error.value = e.message }
}

async function remove(row) {
  if (!window.confirm('删除这段录音？该操作不可撤销。')) return
  try {
    await audioApi.remove(row.id)
    if (playUrls.value[row.id]) URL.revokeObjectURL(playUrls.value[row.id])
    notify('录音已删除')
    await refresh()
  } catch (e) { error.value = e.message; notify('删除失败：' + e.message) }
}

watch(meetingId, () => {
  clearClip()
  Object.values(playUrls.value).forEach(u => URL.revokeObjectURL(u))
  playUrls.value = {}
  refresh()
}, { immediate: true })

onUnmounted(() => {
  if (recording.value) stop()
  clearClip()
  Object.values(playUrls.value).forEach(u => URL.revokeObjectURL(u))
})
</script>

<template>
  <div class="recorder" id="meeting-recorder">
    <p class="small">
      <b>会议录音</b> · 浏览器调用麦克风录制，直接编码为 <b>.mp3</b> 后提交到当前会议；也可选择本地 .mp3 文件提交。
      音频随会议留存，可回放/下载；删除仅管理员与负责人。
    </p>

    <div class="rec-actions">
      <button v-if="!recording" class="primary" id="rec-start" :disabled="!meetingId || busy || !maySubmit" @click="start">
        ● 开始录音
      </button>
      <button v-else class="danger" id="rec-stop" @click="stop">■ 停止录音（{{ fmtMs(elapsedMs) }}）</button>
      <label class="file-pick">
        <input type="file" accept=".mp3,audio/mpeg" :disabled="!meetingId || busy || !maySubmit" @change="submitFile">
        <span>选择本地 .mp3 提交</span>
      </label>
      <span v-if="recording" class="rec-live"><i class="dot"></i>录音中 · {{ fmtMs(elapsedMs) }}</span>
      <span v-else-if="busy" class="small">处理中…</span>
    </div>

    <p v-if="!meetingId" class="small">请先选择或保存一个会议，再录音。</p>
    <p v-if="!supported" class="small">提示：当前浏览器不支持录音（请用 Chrome/Edge 并允许麦克风）。仍可用本地 .mp3 提交。</p>
    <p v-if="error" role="alert" class="err">{{ error }}</p>

    <div v-if="clip" class="clip">
      <div class="clip-head">待提交录音 · {{ fmtMs(clip.ms) }} · {{ fmtSize(clip.bytes) }}（已编码 MP3）</div>
      <audio :src="clip.url" controls preload="metadata"></audio>
      <div class="clip-actions">
        <button class="primary" id="rec-submit" :disabled="busy || !maySubmit" @click="submitClip">提交到会议</button>
        <a :href="clip.url" download="录音.mp3" class="btn-link">下载 mp3</a>
        <button @click="clearClip">丢弃</button>
      </div>
    </div>

    <div class="h-sec" style="margin-top:14px">会议已有音频（{{ audios.length }}）</div>
    <div v-if="!audios.length" class="small">暂无音频。</div>
    <div v-for="a in audios" :key="a.id" class="audio-row">
      <div class="audio-meta">
        <b>{{ a.filename }}</b>
        <span class="small">
          {{ a.source === 'recorder' ? '网页录音' : '本地上传' }} ·
          {{ fmtSize(a.byte_size) }}<template v-if="a.duration_ms"> · {{ fmtMs(a.duration_ms) }}</template> ·
          sha256 {{ (a.sha256 || '').slice(0, 10) }}…
        </span>
      </div>
      <div class="audio-actions">
        <audio v-if="playUrls[a.id]" :src="playUrls[a.id]" controls preload="metadata"></audio>
        <button v-else @click="play(a)">加载回放</button>
        <a v-if="playUrls[a.id]" :href="playUrls[a.id]" :download="a.filename" class="btn-link">下载</a>
        <button v-if="mayDelete" class="danger" @click="remove(a)">删除</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.recorder { border: 1px solid var(--line, #d8d8d8); border-radius: 10px; padding: 12px; margin: 10px 0; }
.rec-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin: 8px 0; }
.rec-actions button { min-width: 120px; }
.file-pick { position: relative; overflow: hidden; display: inline-flex; align-items: center; border: 1px dashed var(--line, #bbb); border-radius: 8px; padding: 6px 10px; cursor: pointer; font-size: 13px; }
.file-pick input { position: absolute; inset: 0; opacity: 0; cursor: pointer; }
.rec-live { display: inline-flex; align-items: center; gap: 6px; color: #b3261e; font-weight: 600; }
.rec-live .dot { width: 9px; height: 9px; border-radius: 50%; background: #b3261e; animation: recpulse 1s infinite; }
@keyframes recpulse { 50% { opacity: 0.25 } }
.err { color: #b3261e; }
.clip { margin: 10px 0; padding: 10px; border-radius: 8px; background: rgba(0,0,0,0.04); }
.clip-head { font-size: 13px; margin-bottom: 6px; font-weight: 600; }
.clip-actions { display: flex; gap: 10px; align-items: center; margin-top: 6px; flex-wrap: wrap; }
.audio-row { display: flex; justify-content: space-between; gap: 12px; align-items: center; padding: 8px 0; border-bottom: 1px dashed var(--line, #e2e2e2); flex-wrap: wrap; }
.audio-meta { display: flex; flex-direction: column; gap: 2px; min-width: 240px; }
.audio-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.btn-link { font-size: 13px; }
</style>
