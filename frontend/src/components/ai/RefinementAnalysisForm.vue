<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useMeetingStore } from '@/stores/meeting'
import { useSessionStore } from '@/stores/session'
import { analyzeRefinement } from '@/api/refinementAnalysis'

const props = defineProps({ meetingId: String })
const emit = defineEmits(['saved'])
const meeting = useMeetingStore()
const session = useSessionStore()
const sprint = ref(null)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const intent = ref(null)
const persistenceError = ref(false)
const key = computed(() => `aicap-refinement-analysis:${session.currentUser?.id}:${props.meetingId}:${sprint.value ?? 'none'}`)
let version = 0

function persist(storageKey, value) {
  try { sessionStorage.setItem(storageKey, JSON.stringify(value)); return true } catch { return false }
}
watch(key, () => {
  ++version
  busy.value = false
  error.value = ''; notice.value = ''; intent.value = null
  persistenceError.value = false
  try {
    const saved = JSON.parse(sessionStorage.getItem(key.value) || 'null')
    if (saved && /^[a-z0-9-]{1,80}$/.test(saved.id) && typeof saved.completed === 'boolean') intent.value = saved
  } catch { persistenceError.value = true }
}, { immediate: true })
onBeforeUnmount(() => { ++version })

async function analyze() {
  if (busy.value || !meeting.maySubmit || !props.meetingId) return
  if (!intent.value || intent.value.completed) intent.value = { id: crypto.randomUUID(), completed: false }
  const attempt = { ...intent.value }
  const storageKey = key.value
  // Persist before sending so a reload can recover an uncertain request.
  if (!persist(storageKey, attempt)) {
    persistenceError.value = true
    error.value = '无法保存本次请求编号，请允许浏览器会话存储后重试。'
    return
  }
  persistenceError.value = false
  const current = ++version
  busy.value = true; error.value = ''; notice.value = ''
  try {
    const result = await analyzeRefinement(props.meetingId, {
      client_request_id: attempt.id, meeting_type: 'backlog_refinement', current_sprint: sprint.value
    })
    // If completion cannot be persisted, leave the original incomplete key for safe recovery.
    persist(storageKey, { ...attempt, completed: true })
    if (current !== version) return
    intent.value = { ...attempt, completed: true }
    notice.value = result.storage_status === 'no_changes' ? '分析已保存，本次没有新故事提案。' : '分析已保存，请在下方查看并审核提案。'
    emit('saved', result.analysis_id)
  } catch (err) {
    if (current === version) error.value = `未能确认分析保存结果：${err.message}。重试将沿用本次请求编号。`
  } finally {
    if (current === version) busy.value = false
  }
}
</script>

<template>
  <section aria-label="Backlog Refinement分析">
    <h3>Backlog Refinement分析</h3>
    <p class="small">识别明确确认的新增需求，保留待确认字段；人工补齐并审核后，再单独执行创建故事。</p>
    <template v-if="meeting.maySubmit">
      <label class="field">本次 Sprint（可选）
        <select v-model="sprint" :disabled="busy">
          <option :value="null">未指定</option>
          <option v-for="value in 4" :key="value" :value="value">Sprint {{ value }}</option>
        </select>
      </label>
      <button class="primary" type="button" :disabled="busy || !meetingId" @click="analyze">{{ busy ? '正在分析并保存…' : intent?.completed ? '重新分析Backlog Refinement' : intent ? '重试本次分析' : '分析Backlog Refinement' }}</button>
      <p v-if="persistenceError" class="small">需要浏览器会话存储以保留重试编号。</p>
    </template>
    <p v-else class="small">负责人、管理员或成员可发起分析。</p>
    <p v-if="!meetingId" class="small">请先选择已保存会议。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
  </section>
</template>
