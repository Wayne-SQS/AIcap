<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMeetingStore } from '@/stores/meeting'
import MeetingPanel from '@/components/ai/MeetingPanel.vue'
const route = useRoute()
const router = useRouter()
const meeting = useMeetingStore()
defineProps({ embedded: Boolean })
const page = ref(null)
const sourceSuggestion = computed(() => meeting.suggestions.find(item =>
  item.id === route.query.suggestionId && item.meeting_id === route.query.meetingId))
let sourceObserver
function focusSource() {
  sourceObserver?.disconnect()
  sourceObserver = null
  if (route.query.fromReview !== '1' || !page.value) return
  const general = route.query.sourceType === 'general'
  const id = general ? route.query.suggestionId : route.query.proposalId
  if (!id) return
  const selector = general ? '[data-source-suggestion]' : '[data-source-proposal]'
  const locate = () => {
    const target = [...page.value.querySelectorAll(selector)]
      .find(item => item.getAttribute(general ? 'data-source-suggestion' : 'data-source-proposal') === id)
    if (!target) return false
    sourceObserver?.disconnect()
    sourceObserver = null
    const reviewForm = !general && meeting.mayReview ? target.querySelector('form[aria-label]') : null
    const action = reviewForm || (!general && meeting.mayReview
      ? [...target.querySelectorAll('button')].find(button => button.textContent?.includes('执行已批准')) : null)
    const destination = action || target
    destination.scrollIntoView({ block: action ? 'center' : 'start' })
    const focus = reviewForm?.querySelector('select') || action || target
    focus.focus({ preventScroll: true })
    return true
  }
  if (!locate()) {
    sourceObserver = new MutationObserver(locate)
    sourceObserver.observe(page.value, { childList: true, subtree: true })
  }
}
function returnToReview() {
  router.push({ name: 'review', query: {
    status: route.query.status || 'pending', source: route.query.source || 'all',
    search: route.query.search || '', fromDate: route.query.fromDate || '', toDate: route.query.toDate || ''
  } })
}
watch(() => route.query.meetingId, id => { if (id && meeting.meetings.some(m => m.id === id)) meeting.select(id) }, { immediate: true })
watch(() => meeting.online, async value => {
  if (!value) return
  try { await meeting.loadAll(); const id = route.query.meetingId; if (id && meeting.meetings.some(m => m.id === id)) meeting.select(id) } catch {}
}, { immediate: true })
watch(() => [route.query.meetingId, route.query.analysisId, route.query.proposalId, route.query.suggestionId],
  () => nextTick(focusSource))
onMounted(() => nextTick(focusSource))
onBeforeUnmount(() => sourceObserver?.disconnect())
</script>
<template><section ref="page" class="view" :class="{ 'embedded-agent-view': embedded }"><div v-if="!embedded" class="hero"><div><div class="eyebrow">MEETING WORKSPACE / 会议流程</div><h1>会议工作区</h1><p>选择文本会议、语音会议或手动录入建议。分析、审核和业务执行是独立步骤。</p><button v-if="route.query.fromReview === '1'" type="button" @click="returnToReview">← 返回审核中心（保留筛选）</button><p v-if="route.query.fromReview === '1'" class="small">从审核中心打开 · {{ meeting.selectedMeeting?.title || '正在加载会议' }} · <span v-if="route.query.sourceType === 'general'">通用会议建议</span><span v-else>{{ route.query.type }} 分析</span></p></div></div>
  <article v-if="route.query.sourceType === 'general' && sourceSuggestion" class="source-suggestion" :data-source-suggestion="sourceSuggestion.id" tabindex="-1">
    <h2>来源会议建议 · {{ sourceSuggestion.id }}</h2>
    <p>会议：{{ meeting.selectedMeeting?.title || sourceSuggestion.meeting_title }} · 状态：{{ sourceSuggestion.status === 'pending' ? '待审核' : sourceSuggestion.status === 'approved' ? '已采纳' : '已拒绝' }}</p>
    <p>建议：{{ sourceSuggestion.changes?.title }} · 优先级：{{ sourceSuggestion.changes?.priority || '未填写' }}</p>
    <p v-if="sourceSuggestion.note">说明：{{ sourceSuggestion.note }}</p>
    <blockquote>原文证据：{{ sourceSuggestion.evidence }}</blockquote>
    <p v-if="sourceSuggestion.reason">审核理由：{{ sourceSuggestion.reason }}</p>
    <p v-if="sourceSuggestion.status === 'pending' && !meeting.mayReview">当前账号只能查看；请使用管理员或负责人账号在审核中心采纳或拒绝。</p>
    <p v-else-if="sourceSuggestion.status === 'pending'">请返回审核中心采纳、修改后采纳或拒绝。</p>
  </article>
  <MeetingPanel />
</section></template>
<style scoped>
.source-suggestion { margin: 16px 0; padding: 18px; border: 2px solid var(--ink, #24352b); background: var(--paper, #f7f4ea); scroll-margin-top: 90px; }
.source-suggestion:focus { outline: 3px solid var(--accent, #a5c977); outline-offset: 3px; }
:deep([data-source-proposal]) { scroll-margin-top: 90px; }
:deep([data-source-proposal]:focus) { outline: 3px solid var(--accent, #a5c977); outline-offset: 3px; }
</style>
