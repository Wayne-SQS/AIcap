<script setup>
import { reactive, ref, nextTick } from 'vue'

/* 修改后采纳弹窗:对齐 meeting-improvements.js workflowDialog(L27-40)
   原始建议和会议证据保留,确认内容经人工修改后写入需求池 */
const dialogEl = ref(null)
const form = reactive({ title: '', description: '', priority: 'Could', reason: '' })
const evidence = ref('')
let resolver = null

function open(s) {
  return new Promise(resolve => {
    resolver = resolve
    evidence.value = s.evidence
    form.title = s.changes.title
    form.description = s.changes.description
    form.priority = s.changes.priority
    form.reason = ''
    nextTick(() => { dialogEl.value?.showModal() })
  })
}
function submit() {
  const { reason, ...changes } = { reason: form.reason, title: form.title, description: form.description, priority: form.priority }
  const r = resolver; resolver = null
  dialogEl.value?.close()
  if (r) r({ reason, changes })
}
function cancel() {
  const r = resolver; resolver = null
  dialogEl.value?.close()
  if (r) r(null)
}
defineExpose({ open })
</script>

<template>
  <dialog ref="dialogEl" @close="cancel">
    <form @submit.prevent="submit">
      <h3>修改后采纳</h3>
      <p class="small">原始建议和会议证据会保留；以下内容经你确认后写入需求池。</p>
      <blockquote>{{ evidence }}</blockquote>
      <label class="field">需求标题<input name="title" v-model="form.title" required maxlength="200"></label>
      <label class="field">需求描述<textarea name="description" v-model="form.description" maxlength="10000" rows="5"></textarea></label>
      <label class="field">优先级<select name="priority" v-model="form.priority"><option v-for="p in ['Could', 'Should', 'Must']" :key="p" :value="p">{{ p }}</option></select></label>
      <label class="field">修改与审核理由<textarea name="reason" v-model="form.reason" maxlength="1000" rows="2"></textarea></label>
      <div class="sug-acts"><button type="button" @click="cancel">取消</button><button type="submit" class="primary">确认</button></div>
    </form>
  </dialog>
</template>
