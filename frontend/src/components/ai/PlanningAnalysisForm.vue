<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { useMeetingStore } from '@/stores/meeting'
import { analyzePlanning, planningSaveIntentApi } from '@/api/planningAnalysis'

const props = defineProps({ meetingId: String })
const emit = defineEmits(['saved'])
const meeting = useMeetingStore()
const sprint = ref(null)
const targetSprint = ref(null)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const intent = ref(null)
const intentReady = ref(false)
let version = 0

async function loadIntent() {
  const current = ++version
  intent.value = null; intentReady.value = false; error.value = ''; notice.value = ''
  if (!props.meetingId || !meeting.maySubmit) { intentReady.value = true; return }
  try {
    const saved = await planningSaveIntentApi.get(props.meetingId)
    if (current !== version) return
    intent.value = saved
    if (saved) { sprint.value = saved.current_sprint; targetSprint.value = saved.target_sprint }
    intentReady.value = true
  } catch (err) {
    if (current === version) error.value = `读取待恢复请求失败：${err.message}。请重试读取后再分析。`
  }
}
watch(() => [props.meetingId, meeting.maySubmit], loadIntent, { immediate: true })
onBeforeUnmount(() => { ++version })

async function analyze() {
  if (busy.value || !intentReady.value || !meeting.maySubmit || !props.meetingId) return
  const attempt = intent.value || {
    client_request_id: crypto.randomUUID(), meeting_type: 'sprint_planning',
    current_sprint: sprint.value, target_sprint: targetSprint.value
  }
  intent.value = attempt
  const current = ++version
  busy.value = true; error.value = ''; notice.value = ''
  try {
    const saved = await planningSaveIntentApi.put(props.meetingId, attempt)
    if (current !== version) return
    intent.value = saved
  } catch (err) {
    if (current === version) error.value = `分析未发出：${err.message}。重试沿用原请求编号；也可读取服务端待恢复请求核对。`
    if (current === version) busy.value = false
    return
  }
  try {
    const result = await analyzePlanning(props.meetingId, attempt)
    if (current !== version) return
    notice.value = result.storage_status === 'no_changes' ? '分析已保存，本次没有Sprint变更提案。' : '分析已保存，请在下方查看并审核提案。'
    emit('saved', result.analysis_id)
    try {
      await planningSaveIntentApi.clear(props.meetingId, attempt.client_request_id)
      if (current === version) intent.value = null
    } catch (err) {
      if (current === version) error.value = `分析已保存，但待恢复请求清理失败：${err.message}。可重试原请求。`
    }
  } catch (err) {
    if (current === version) error.value = `未能确认分析保存结果：${err.message}。重试将沿用服务端保存的原条件和请求编号。`
  } finally {
    if (current === version) busy.value = false
  }
}

async function discardIntent() {
  if (!intent.value || busy.value || !window.confirm('放弃待确认的 Planning 请求？请先核对分析记录中是否已有保存结果。')) return
  const current = ++version
  busy.value = true; error.value = ''; notice.value = ''
  try {
    await planningSaveIntentApi.clear(props.meetingId, intent.value.client_request_id)
    if (current !== version) return
    intent.value = null
    notice.value = '已放弃待恢复请求。请刷新分析记录核对是否已有结果。'
  } catch (err) { if (current === version) error.value = `放弃请求失败：${err.message}` }
  finally { if (current === version) busy.value = false }
}
</script>

<template>
  <section aria-label="Sprint Planning分析">
    <h3>Sprint Planning分析</h3>
    <p class="small">分析已保存的会议原文并生成故事Sprint提案；提案需经人工审核和执行。</p>
    <template v-if="meeting.maySubmit">
      <label class="field">目标 Sprint（可选）
        <select v-model="targetSprint" :disabled="busy || !!intent">
          <option :value="null">未指定</option>
          <option v-for="value in 4" :key="value" :value="value">Sprint {{ value }}</option>
        </select>
      </label>
      <label class="field">本次 Sprint（可选）
        <select v-model="sprint" :disabled="busy || !!intent">
          <option :value="null">未指定</option>
          <option v-for="value in 4" :key="value" :value="value">Sprint {{ value }}</option>
        </select>
      </label>
      <button class="primary" type="button" :disabled="busy || !meetingId || !intentReady" @click="analyze">{{ busy ? '正在分析并保存…' : intent ? '重试本次分析' : '分析Sprint Planning' }}</button>
      <p v-if="intent">待恢复请求 {{ intent.client_request_id }}：重试沿用原 Sprint 条件。</p>
      <button v-if="intent" type="button" :disabled="busy" @click="discardIntent">放弃待恢复请求</button>
      <button v-if="intent && error" type="button" :disabled="busy" @click="loadIntent">核对服务端待恢复请求</button>
      <button v-if="!intentReady && !busy" type="button" @click="loadIntent">重试读取待恢复请求</button>
    </template>
    <p v-else class="small">负责人、管理员或成员可发起分析。</p>
    <p v-if="!meetingId" class="small">请先选择已保存会议。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
  </section>
</template>
