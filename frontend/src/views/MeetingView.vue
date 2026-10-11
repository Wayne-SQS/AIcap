<script setup>
import { watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMeetingStore } from '@/stores/meeting'
import MeetingPanel from '@/components/ai/MeetingPanel.vue'
const route = useRoute()
const router = useRouter()
const meeting = useMeetingStore()
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
</script>
<template><section class="view"><div class="hero"><div><div class="eyebrow">MEETING WORKSPACE / 会议流程</div><h1>会议工作区</h1><p>选择文本会议、语音会议或手动录入建议。分析、审核和业务执行是独立步骤。</p><button v-if="route.query.fromReview === '1'" type="button" @click="returnToReview">← 返回审核中心（保留筛选）</button><p v-if="route.query.fromReview === '1'" class="small">从审核中心打开 · {{ meeting.selectedMeeting?.title || '正在加载会议' }} · <span v-if="route.query.sourceType === 'general'">通用会议建议</span><span v-else>{{ route.query.type }} 分析</span></p></div></div><MeetingPanel /></section></template>
